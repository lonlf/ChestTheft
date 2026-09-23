package com.lonleaf.chesttheft.protection;

import cn.lunadeer.dominion.api.DominionAPI;
import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.MemberDTO;
import cn.lunadeer.dominion.api.dtos.PlayerDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.events.dominion.DominionCreateEvent;
import cn.lunadeer.dominion.providers.MemberProvider;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Dominion 领地保护适配器（软依赖）：领地创建自动卸锁 + 打开前事件内临时授予箱子 flag（版本自适应）。
 * 成员/flag 为持久化写入（Dominion 库），授权前落库（记录原成员状态），崩溃后启动清理恢复。
 */
public class DominionAdapter extends TempAccessAdapter implements Listener {

    /** 是否已注册监听。 */
    private boolean registered = false;

    private final PluginConfig config;
    /** 同步等待 Dominion 异步授权接口的超时（毫秒）：Dominion 无同步 addMember API，事件内授权只能阻塞等待；
     *  取配置 protection.dominion-sync-timeout（秒）作为上限（默认 1 秒），将最坏主线程卡顿从 3 秒降到 1 秒；
     *  配置 0 时立即超时放弃授权并回滚（玩家本次打开被拦截，下次重试），彻底消除主线程卡顿。 */
    private final long grantTimeoutMs;

    public DominionAdapter(Plugin plugin, PluginConfig config, Database database) {
        super(plugin, database);
        this.config = config;
        this.grantTimeoutMs = Math.max(1, (long) (config.getDominionSyncTimeout() * 1000L));
    }

    /** 箱子开关 flag：4.9.4+ 为 CHEST，旧版仅 CONTAINER；反射探测，授予的 flag 必须与检查一致。 */
    private static PriFlag chestFlag() {
        try {
            Field field = Flags.class.getField("CHEST");
            return (PriFlag) field.get(null);
        } catch (ReflectiveOperationException e) {
            return Flags.CONTAINER;
        }
    }

    @Override
    public String pluginType() {
        return "dominion";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("Dominion") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isDominionProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.dominionOwner(block);
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        return ((DomGrant) grant).wasMember ? "1" : "0";
    }

    @Override
    protected boolean revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (!isActive()) {
            return false;
        }
        DominionAPI api = DominionAPI.getInstance();
        DominionDTO dominion = api.getDominion(block.getLocation());
        if (dominion == null) {
            // 领地已不存在：无法确认恢复，保留记录下次重试
            return false;
        }
        MemberDTO member = api.getMember(dominion, playerUuid);
        if (member == null) {
            // 成员未成功添加（或异步撤销已完成），无残留，记录可安全删除
            return true;
        }
        if ("1".equals(extra)) {
            // 原本是成员：仅撤销容器 flag（同步接口，返回是否确认成功）
            return setMemberFlagDirect(dominion, member, false);
        }
        // 临时添加的成员：官方接口为异步，无法同步确认撤销结果——保守保留记录，
        // 下次启动时若异步撤销已完成（member==null）即可清理
        MemberProvider.getInstance().setMemberFlag(null, dominion, member, chestFlag(), false)
                .thenAccept(m -> {
                    if (m != null) {
                        MemberProvider.getInstance().removeMember(null, dominion, m);
                    }
                });
        return false;
    }

    /** 注册 Dominion 领地创建事件监听（仅 Dominion 插件存在时）。 */
    @Override
    public void register(Consumer<Block> protectionCreatedHandler) {
        super.register(protectionCreatedHandler);
        if (isActive() && !registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    @Override
    public void unregister() {
        registered = false;
        cleanup();
    }

    // ==================== 打开容器：临时授予箱子 flag ====================

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (!isActive()) {
            return null;
        }
        DominionAPI api = DominionAPI.getInstance();
        DominionDTO dominion = api.getDominion(block.getLocation());
        if (dominion == null) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        MemberDTO member = api.getMember(dominion, uuid);
        if (member != null && Boolean.TRUE.equals(member.getFlagValue(chestFlag()))) {
            return null; // 玩家已有容器权限
        }
        if (member != null) {
            return new DomGrant(true, member, true);
        }
        // 非成员：apply 阶段先添加成员再授予容器 flag
        PlayerDTO playerDTO = api.getPlayer(uuid);
        if (playerDTO == null) {
            return null;
        }
        return new DomGrant(true, null, false);
    }

    @Override
    protected void apply(Block block, Player player, TempGrant grant) {
        DomGrant domGrant = (DomGrant) grant;
        DominionAPI api = DominionAPI.getInstance();
        DominionDTO dominion = api.getDominion(block.getLocation());
        if (dominion == null) {
            throw new IllegalStateException("领地消失，无法授予容器权限");
        }
        if (domGrant.member != null) {
            // 已成员：直接设置箱子 flag（同步写库触发缓存更新，立即生效）
            setMemberFlagDirect(dominion, domGrant.member, true);
            return;
        }
        // 非成员：先添加成员再授予容器 flag。API 为异步接口（DB 写库在独立线程完成），
        // 若直接返回则授权尚未生效，同一事件链中领地的交互检查会拦截本次打开；
        // 因此同步阻塞等待授权完成后再继续（超时放弃并回滚，避免授权残留）
        PlayerDTO playerDTO = api.getPlayer(player.getUniqueId());
        if (playerDTO == null) {
            throw new IllegalStateException("玩家数据缺失，无法添加为领地成员");
        }
        MemberDTO addedMember;
        try {
            addedMember = MemberProvider.getInstance().addMember(null, dominion, playerDTO)
                    .get(grantTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("添加领地成员失败: " + e.getMessage(), e);
        }
        if (addedMember == null) {
            throw new IllegalStateException("添加领地成员返回空");
        }
        // 与 revoke 用 domGrant 对象锁串行判定：锁内检查撤销标志后再决定授权或清理，
        // 避免授权与撤销乱序导致临时成员带容器 flag 残留
        synchronized (domGrant) {
            if (domGrant.cancelled) {
                // 撤销已先于添加完成发生：清理刚创建的成员，避免成员残留
                MemberProvider.getInstance().removeMember(null, dominion, addedMember);
                return;
            }
            try {
                MemberProvider.getInstance().setMemberFlag(null, dominion, addedMember, chestFlag(), true)
                        .get(grantTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // flag 设置失败：移除刚添加的成员后抛出（基类会删除记录并中止授权）
                MemberProvider.getInstance().removeMember(null, dominion, addedMember);
                throw new IllegalStateException("设置容器 flag 失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        DomGrant domGrant = (DomGrant) grant;
        if (!domGrant.granted || !isActive()) {
            return;
        }
        DominionAPI api = DominionAPI.getInstance();
        DominionDTO dominion = api.getDominion(block.getLocation());
        if (dominion == null) {
            return;
        }
        MemberDTO member = domGrant.member != null ? domGrant.member : api.getMember(dominion, player.getUniqueId());
        if (member == null) {
            // 成员仍在异步添加中：标记取消，由添加完成的回调负责清理，避免成员残留
            if (!domGrant.wasMember) {
                domGrant.cancelled = true;
            }
            return;
        }
        if (domGrant.wasMember) {
            // 原本是成员：仅撤销容器 flag（同步写库，立即生效）
            setMemberFlagDirect(dominion, member, false);
        } else {
            // 临时添加的成员：同一锁内标记取消（防止异步回调再次授权）并发起撤销，
            // 与 apply 回调的授权判定串行，避免乱序导致成员带容器 flag 残留
            synchronized (domGrant) {
                domGrant.cancelled = true;
                MemberProvider.getInstance().setMemberFlag(null, dominion, member, chestFlag(), false)
                        .thenAccept(m -> {
                            if (m != null) {
                                MemberProvider.getInstance().removeMember(null, dominion, m);
                            }
                        });
            }
        }
    }

    /** 同步设置成员 flag（异常时回退官方异步接口）；同步设置成功返回 true，回退异步返回 false（无法确认结果）。 */
    private boolean setMemberFlagDirect(DominionDTO dominion, MemberDTO member, boolean value) {
        try {
            member.setFlagValue(chestFlag(), value);
            return true;
        } catch (Exception e) {
            MemberProvider.getInstance().setMemberFlag(null, dominion, member, chestFlag(), value);
            return false;
        }
    }

    /** Dominion 临时授权记录：携带授权时成员记录与成员资格（撤销时区分恢复方式）。 */
    private static final class DomGrant extends TempGrant {
        /** 授权时已存在的成员记录（临时添加的成员时为 null）。 */
        final MemberDTO member;
        /** 玩家原本是否为领地成员（非成员时撤销需移除成员）。 */
        final boolean wasMember;
        /** 撤销已发生（临时添加的成员异步回调据此不再授权，改为清理）。 */
        volatile boolean cancelled;

        DomGrant(boolean granted, MemberDTO member, boolean wasMember) {
            super(granted);
            this.member = member;
            this.wasMember = wasMember;
        }
    }

    // ==================== 领地创建后自动卸锁 ====================

    /**
     * 监听领地创建事件：仅在实际创建成功后（afterCreated 回调）延迟检查区域内上锁箱子并自动卸锁。
     * MONITOR 优先级仅观察，不干预事件结果；事件被取消时不处理。
     * afterCreated 回调可能在其他线程执行，因此调度回主线程执行扫描。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDominionCreate(DominionCreateEvent event) {
        if (event.isCancelled()) return;
        event.afterCreated(dominion -> {
            if (dominion == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    scanDominionForLockedChests(dominion);
                } catch (Exception e) {
                    plugin.getLogger().fine("Dominion creation auto-unlock check failed: " + e.getMessage());
                }
            });
        });
    }

    /**
     * 扫描领地内所有已加载区块中的箱子，对已上锁的箱子执行自动卸锁回调。
     * 仅检查已加载的区块（未加载的区块说明无活跃玩家，其内的箱子暂不处理）。
     */
    private void scanDominionForLockedChests(DominionDTO dominion) {
        World world = dominion.getWorld();
        if (world == null) {
            UUID worldUid = dominion.getWorldUid();
            if (worldUid != null) {
                world = Bukkit.getWorld(worldUid);
            }
        }
        if (world == null) return;
        CuboidDTO cuboid = dominion.getCuboid();
        if (cuboid == null) return;
        // 领地边界为半开区间 [x1, x2)，上界不含；负坐标用 floorDiv 计算区块范围
        int minChunkX = Math.floorDiv(cuboid.x1(), 16);
        int maxChunkX = Math.floorDiv(cuboid.x2() - 1, 16);
        int minChunkZ = Math.floorDiv(cuboid.z1(), 16);
        int maxChunkZ = Math.floorDiv(cuboid.z2() - 1, 16);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                Chunk chunk = world.getChunkAt(cx, cz);
                // 仅检查方块实体（箱子/陷阱箱均为方块实体），避免按区块全 Y 层遍历造成主线程卡顿
                for (BlockState state : chunk.getTileEntities()) {
                    Block block = state.getBlock();
                    if (inCuboid(cuboid, block.getX(), block.getY(), block.getZ()) && isChest(block)) {
                        // protectionCreatedHandler 内部会通过 chestService.isLocked() 判断，
                        // 仅对上锁的箱子执行自动卸锁
                        protectionCreatedHandler.accept(block);
                    }
                }
            }
        }
    }

    /** 判断坐标是否位于领地内（半开区间 [x1, x2)）。 */
    private boolean inCuboid(CuboidDTO cuboid, int x, int y, int z) {
        return x >= cuboid.x1() && x < cuboid.x2()
                && y >= cuboid.y1() && y < cuboid.y2()
                && z >= cuboid.z1() && z < cuboid.z2();
    }

    /** 判断方块是否为箱子（普通箱子或陷阱箱）。 */
    private boolean isChest(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }
}
