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
import com.lonleaf.chesttheft.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dominion 领地保护集成（软依赖）：领地创建自动卸锁 + 打开前临时授予箱子 flag（版本自适应）。
 * 临时授权记录入库，崩溃后启动清理恢复。
 */
public class DominionProtectionListener extends TempAccessListener implements Listener {

    /** 保护创建回调（自动卸锁逻辑，由 ProtectionListener 提供）。 */
    private final Consumer<Block> protectionCreatedHandler;
    /** 是否已注册监听。 */
    private boolean registered = false;

    /** 箱子开关 flag：4.9.4+ 为 CHEST，旧版仅 CONTAINER；反射探测，授予的 flag 必须与检查一致。 */
    private static PriFlag chestFlag() {
        try {
            Field field = Flags.class.getField("CHEST");
            return (PriFlag) field.get(null);
        } catch (ReflectiveOperationException e) {
            return Flags.CONTAINER;
        }
    }

    public DominionProtectionListener(Plugin plugin, Consumer<Block> protectionCreatedHandler, Database database) {
        super(plugin, database);
        this.protectionCreatedHandler = protectionCreatedHandler;
    }

    @Override
    protected String pluginType() {
        return "dominion";
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        return ((DomGrant) grant).wasMember ? "1" : "0";
    }

    @Override
    protected void revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (Bukkit.getPluginManager().getPlugin("Dominion") == null) {
            return;
        }
        DominionAPI api = DominionAPI.getInstance();
        DominionDTO dominion = api.getDominion(block.getLocation());
        if (dominion == null) {
            return;
        }
        MemberDTO member = api.getMember(dominion, playerUuid);
        if (member == null) {
            return; // 成员未成功添加，无残留
        }
        if ("1".equals(extra)) {
            // 原本是成员：仅撤销容器 flag
            setMemberFlagDirect(dominion, member, false);
        } else {
            // 临时添加的成员：撤销容器 flag 后移除成员
            MemberProvider.getInstance().setMemberFlag(null, dominion, member, chestFlag(), false)
                    .thenAccept(m -> {
                        if (m != null) {
                            MemberProvider.getInstance().removeMember(null, dominion, m);
                        }
                    });
        }
    }

    /** 注册 Dominion 领地创建事件监听（仅 Dominion 插件存在时）。 */
    public void register() {
        if (Bukkit.getPluginManager().getPlugin("Dominion") != null && !registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    /** 注销监听（插件禁用时由 Bukkit 自动注销，此处标记状态并清理临时授权）。 */
    public void unregister() {
        registered = false;
        cleanup();
    }

    // ==================== 打开容器：临时授予箱子 flag ====================

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (Bukkit.getPluginManager().getPlugin("Dominion") == null) {
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
        // 非成员：先添加成员再授予容器 flag（官方异步接口，缓存更新后生效）
        PlayerDTO playerDTO = api.getPlayer(player.getUniqueId());
        if (playerDTO == null) {
            throw new IllegalStateException("玩家数据缺失，无法添加为领地成员");
        }
        MemberProvider.getInstance().addMember(null, dominion, playerDTO)
                .thenAccept(m -> {
                    if (m == null) {
                        return;
                    }
                    // 与 revoke 用 domGrant 对象锁串行判定：锁内检查撤销标志后再决定授权或清理，
                    // 避免异步 setMemberFlag(true) 与 revoke 的撤销乱序，导致临时成员带容器 flag 残留
                    synchronized (domGrant) {
                        if (domGrant.cancelled) {
                            // 撤销已先于添加完成发生：清理刚创建的成员，避免成员残留
                            MemberProvider.getInstance().removeMember(null, dominion, m);
                            return;
                        }
                        MemberProvider.getInstance().setMemberFlag(null, dominion, m, chestFlag(), true);
                    }
                });
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        DomGrant domGrant = (DomGrant) grant;
        if (!domGrant.granted || Bukkit.getPluginManager().getPlugin("Dominion") == null) {
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

    /** 同步设置成员 flag（异常时回退官方异步接口）。 */
    private void setMemberFlagDirect(DominionDTO dominion, MemberDTO member, boolean value) {
        try {
            member.setFlagValue(chestFlag(), value);
        } catch (Exception e) {
            MemberProvider.getInstance().setMemberFlag(null, dominion, member, chestFlag(), value);
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
        int minY = Math.max(cuboid.y1(), world.getMinHeight());
        int maxY = Math.min(cuboid.y2() - 1, world.getMaxHeight() - 1);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                Chunk chunk = world.getChunkAt(cx, cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            int bx = (cx << 4) + x;
                            int bz = (cz << 4) + z;
                            if (!inCuboid(cuboid, bx, y, bz)) continue;
                            Block block = chunk.getBlock(x, y, z);
                            if (isChest(block)) {
                                // protectionCreatedHandler 内部会通过 chestService.isLocked() 判断，
                                // 仅对上锁的箱子执行自动卸锁
                                protectionCreatedHandler.accept(block);
                            }
                        }
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
