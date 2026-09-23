package com.lonleaf.chesttheft.protection;

import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
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

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Residence 领地保护适配器（软依赖）：领地创建自动卸锁 + 打开前事件内临时授予 container flag。
 * flag 为持久化写入（Residence 库），授权前落库（记录原值），崩溃后启动清理恢复。
 */
public class ResidenceAdapter extends TempAccessAdapter implements Listener {

    /** 是否已注册监听。 */
    private boolean registered = false;

    public ResidenceAdapter(Plugin plugin, Database database) {
        super(plugin, database);
    }

    @Override
    public String pluginType() {
        return "residence";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("Residence") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isResidenceProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.residenceOwner(block);
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        // 授权前玩家原 flag 值只可能为 false 或 null（TRUE 时直接跳过授权）
        return ((ResGrant) grant).original == null ? "" : "false";
    }

    @Override
    protected boolean revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (Bukkit.getPluginManager().getPlugin("Residence") == null) {
            return false;
        }
        ClaimedResidence res = ResidenceApi.getResidenceManager().getByLoc(block.getLocation());
        if (res == null) {
            // 领地已不存在：无法确认恢复，保留记录下次重试
            return false;
        }
        FlagPermissions perms = res.getPermissions();
        FlagPermissions.FlagState state = (extra == null || extra.isEmpty())
                ? FlagPermissions.FlagState.NEITHER : FlagPermissions.FlagState.FALSE;
        perms.setPlayerFlag(playerUuid, Flags.container.getName(), state);
        return true;
    }

    /** 注册 Residence 领地创建事件监听（仅 Residence 插件存在时）。 */
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

    // ==================== 打开容器：临时授予 container flag ====================

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (!isActive()) {
            return null;
        }
        ClaimedResidence res = ResidenceApi.getResidenceManager().getByLoc(block.getLocation());
        if (res == null) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        FlagPermissions perms = res.getPermissions();
        Map<String, Boolean> playerFlags = perms.getPlayerFlags(uuid);
        Boolean original = playerFlags == null ? null : playerFlags.get(Flags.container.getName());
        if (Boolean.TRUE.equals(original)) {
            return null; // 玩家已有 container 权限
        }
        return new ResGrant(original);
    }

    @Override
    protected void apply(Block block, Player player, TempGrant grant) {
        ClaimedResidence res = ResidenceApi.getResidenceManager().getByLoc(block.getLocation());
        if (res == null) {
            throw new IllegalStateException("领地消失，无法授予容器权限");
        }
        res.getPermissions().setPlayerFlag(player.getUniqueId(), Flags.container.getName(), FlagPermissions.FlagState.TRUE);
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        ResGrant resGrant = (ResGrant) grant;
        if (!resGrant.granted || !isActive()) {
            return;
        }
        ClaimedResidence res = ResidenceApi.getResidenceManager().getByLoc(block.getLocation());
        if (res == null) {
            return;
        }
        FlagPermissions perms = res.getPermissions();
        UUID uuid = player.getUniqueId();
        if (resGrant.original == null) {
            // 原本未设置：清除临时设置
            perms.setPlayerFlag(uuid, Flags.container.getName(), FlagPermissions.FlagState.NEITHER);
        } else {
            // 原本有明确设置：恢复原值
            perms.setPlayerFlag(uuid, Flags.container.getName(),
                    resGrant.original ? FlagPermissions.FlagState.TRUE : FlagPermissions.FlagState.FALSE);
        }
    }

    /** Residence 临时授权记录：携带玩家原有 container flag 值（撤销时恢复）。 */
    private static final class ResGrant extends TempGrant {
        /** 玩家原有 container flag 值；null 表示原本未设置。 */
        final Boolean original;

        ResGrant(Boolean original) {
            super(true);
            this.original = original;
        }
    }

    // ==================== 领地创建后自动卸锁 ====================

    /**
     * 监听领地创建事件：领地创建后延迟 1 tick 检查区域内上锁箱子并自动卸锁。
     * MONITOR 优先级仅观察，不干预事件结果；事件被取消时不处理。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onResidenceCreate(ResidenceCreationEvent event) {
        if (event.isCancelled()) return;
        // 事件触发时领地可能尚未完全注册到管理器，延迟 1 tick 后通过名称查找
        final String resName = event.getResidenceName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ClaimedResidence res = ResidenceApi.getResidenceManager().getByName(resName);
                if (res == null) return;
                scanResidenceForLockedChests(res);
            } catch (Exception e) {
                plugin.getLogger().fine("Residence creation auto-unlock check failed: " + e.getMessage());
            }
        });
    }

    /**
     * 扫描领地内所有已加载区块中的箱子，对已上锁的箱子执行自动卸锁回调。
     * 仅检查已加载的区块（未加载的区块说明无活跃玩家，其内的箱子暂不处理）。
     */
    private void scanResidenceForLockedChests(ClaimedResidence res) {
        World world = Bukkit.getWorld(res.getWorld());
        if (world == null) return;
        for (ResidenceManager.ChunkRef chunkRef : res.getChunks()) {
            int cx = chunkRef.getX();
            int cz = chunkRef.getZ();
            if (!world.isChunkLoaded(cx, cz)) continue;
            Chunk chunk = world.getChunkAt(cx, cz);
            // 仅检查方块实体（箱子/陷阱箱均为方块实体），避免按区块全 Y 层遍历造成主线程卡顿
            for (BlockState state : chunk.getTileEntities()) {
                Block block = state.getBlock();
                if (isChest(block) && res.containsLoc(block.getLocation())) {
                    // protectionCreatedHandler 内部会通过 chestService.isLocked() 判断，
                    // 仅对上锁的箱子执行自动卸锁
                    protectionCreatedHandler.accept(block);
                }
            }
        }
    }

    /** 判断方块是否为箱子（普通箱子或陷阱箱）。 */
    private boolean isChest(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }
}
