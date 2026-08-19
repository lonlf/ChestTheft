package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.database.TempGrantRecord;
import com.lonleaf.chesttheft.event.ChestOpenEvent;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 领地类 / WorldGuard / NoBuildPlus 保护插件"打开容器临时授权"基类：
 * 先落库后授权（任意时刻断电有记录）、关闭/退出撤销、启动按记录清理崩溃残留。
 */
public abstract class TempAccessListener implements Listener {

    protected final Plugin plugin;
    protected final Database database;
    /** 临时授权记录：箱子位置 → 玩家 → 授权信息；玩家关闭容器或退出时恢复。 */
    private final Map<BlockLocation, Map<UUID, TempGrant>> temporaryGrants = new HashMap<>();

    protected TempAccessListener(Plugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * 打开容器前的只读检查：计算将要授予的权限信息（不执行任何写入）。
     *
     * @return 授权信息（随后由 {@link #apply} 写入，关闭时用于恢复）；方块不在本插件保护范围内或
     * 玩家已有权限时返回 null（不授权、不记录）。
     */
    protected abstract TempGrant prepare(Block block, Player player);

    /**
     * 执行临时授权写入。仅在 {@link #prepare} 返回非 null 且数据库记录已落库后调用；
     * 写入失败时应自行回滚已写入的部分，基类随后会删除对应记录。
     */
    protected abstract void apply(Block block, Player player, TempGrant grant);

    /** 撤销一次临时授权（恢复原状）。 */
    protected abstract void revoke(Block block, Player player, TempGrant grant);

    /** 本插件在数据库临时授权表中的类型标识（如 "worldguard"）。 */
    protected abstract String pluginType();

    /** 序列化授权信息为数据库 extra 字段（启动清理时传给 {@link #revokeFromRecord}）。 */
    protected abstract String serializeGrant(TempGrant grant);

    /**
     * 依据数据库记录撤销一次崩溃残留的授权（启动清理时调用）。
     * 玩家可能不在线，因此应使用 UUID 级 API 恢复；无需恢复原状时返回即可。
     */
    protected abstract void revokeFromRecord(Block block, UUID playerUuid, String extra);

    @EventHandler(priority = EventPriority.LOWEST)
    public final void onChestOpen(ChestOpenEvent event) {
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        BlockLocation location = BlockLocation.from(block);
        Map<UUID, TempGrant> grants = temporaryGrants.computeIfAbsent(location, k -> new HashMap<>());
        // 上次打开被其他插件拦截未真正发生（无关闭事件撤销）：先恢复旧授权（含数据库记录清理）
        if (grants.containsKey(uuid)) {
            revokeGrant(block, player, grants.remove(uuid));
        }
        TempGrant grant;
        try {
            grant = prepare(block, player);
        } catch (Exception e) {
            plugin.getLogger().fine("临时授权检查失败: " + e.getMessage());
            return;
        }
        if (grant == null) {
            return;
        }
        // 先落库、后写入权限：任意时刻断电都有记录可供启动清理（崩溃保险）
        try {
            database.recordTempGrant(pluginType(), location, uuid, serializeGrant(grant));
        } catch (Exception e) {
            plugin.getLogger().fine("记录临时授权失败: " + e.getMessage());
        }
        try {
            apply(block, player, grant);
        } catch (Exception e) {
            // 授权写入失败：子类已回滚已写入的部分，删除记录避免留下无权限的无效记录
            plugin.getLogger().fine("临时授权写入失败: " + e.getMessage());
            try {
                database.deleteTempGrant(pluginType(), location, uuid);
            } catch (Exception ignored) {
            }
            return;
        }
        grants.put(uuid, grant);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("临时授予容器打开权限: " + player.getName() + " @ " + location);
        }
    }

    @EventHandler
    public final void onInventoryClose(InventoryCloseEvent event) {
        if (temporaryGrants.isEmpty() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        List<Block> blocks = getHolderBlocks(event.getInventory());
        if (blocks.isEmpty()) {
            return;
        }
        revokeTemporaryAccess(player, blocks);
    }

    @EventHandler
    public final void onPlayerQuit(PlayerQuitEvent event) {
        revokeTemporaryAccess(event.getPlayer(), null);
    }

    /** 撤销玩家在指定方块上的临时授权；blocks 为 null 时撤销该玩家的全部临时授权（退出场景）。 */
    private void revokeTemporaryAccess(Player player, List<Block> blocks) {
        if (temporaryGrants.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (blocks == null) {
            for (Map.Entry<BlockLocation, Map<UUID, TempGrant>> entry : new HashMap<>(temporaryGrants).entrySet()) {
                TempGrant grant = entry.getValue().remove(uuid);
                if (entry.getValue().isEmpty()) {
                    temporaryGrants.remove(entry.getKey());
                }
                if (grant != null) {
                    revokeGrantByLocation(entry.getKey(), player, grant);
                }
            }
            return;
        }
        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            BlockLocation location = BlockLocation.from(block);
            Map<UUID, TempGrant> grants = temporaryGrants.get(location);
            if (grants == null) {
                continue;
            }
            TempGrant grant = grants.remove(uuid);
            if (grants.isEmpty()) {
                temporaryGrants.remove(location);
            }
            if (grant != null) {
                revokeGrant(block, player, grant);
            }
        }
    }

    /** 按位置反查方块后撤销授权（玩家退出场景）。 */
    private void revokeGrantByLocation(BlockLocation location, Player player, TempGrant grant) {
        World world = location.toWorld();
        if (world == null) {
            return;
        }
        revokeGrant(world.getBlockAt(location.getX(), location.getY(), location.getZ()), player, grant);
    }

    /** 撤销一次临时授权（异常隔离），成功后清理数据库记录。 */
    private void revokeGrant(Block block, Player player, TempGrant grant) {
        try {
            revoke(block, player, grant);
            // 权限已撤销，删除数据库记录（崩溃清理不再处理该条）
            try {
                database.deleteTempGrant(pluginType(), BlockLocation.from(block), player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().fine("撤销临时授权失败: " + e.getMessage());
        }
    }

    /**
     * 插件启动时依据数据库记录清理崩溃残留的授权：
     * 逐条按记录调用 {@link #revokeFromRecord} 恢复原状，成功后删除记录；
     * 恢复失败的记录保留，下次启动重试。
     */
    public void cleanupStale() {
        List<TempGrantRecord> records;
        try {
            records = database.getTempGrants(pluginType());
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_READ_FAIL, e.getMessage()));
            return;
        }
        if (records.isEmpty()) {
            return;
        }
        plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_FOUND, records.size(), pluginType()));
        for (TempGrantRecord record : records) {
            BlockLocation loc = record.getLocation();
            World world = loc.toWorld();
            if (world == null) {
                // 世界已不存在：对应保护数据随世界移除，直接清理记录
                try {
                    database.deleteTempGrant(pluginType(), loc, record.getPlayerUuid());
                } catch (Exception e) {
                    plugin.getLogger().fine("清理失效记录失败: " + e.getMessage());
                }
                continue;
            }
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            try {
                revokeFromRecord(block, record.getPlayerUuid(), record.getExtra());
                database.deleteTempGrant(pluginType(), loc, record.getPlayerUuid());
                plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_CLEANED, pluginType(), loc));
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_CLEAN_FAIL, e.getMessage()));
            }
        }
    }

    /** 插件禁用时清理全部临时授权（避免授权泄漏）。 */
    public void cleanup() {
        if (temporaryGrants.isEmpty()) {
            return;
        }
        for (Map.Entry<BlockLocation, Map<UUID, TempGrant>> entry : new HashMap<>(temporaryGrants).entrySet()) {
            for (Map.Entry<UUID, TempGrant> playerEntry : new HashMap<>(entry.getValue()).entrySet()) {
                Player player = Bukkit.getPlayer(playerEntry.getKey());
                if (player != null && player.isOnline()) {
                    revokeGrantByLocation(entry.getKey(), player, playerEntry.getValue());
                }
            }
        }
        temporaryGrants.clear();
    }

    // ==================== 双箱子支持 ====================

    /** 从打开的容器反查方块：双箱子返回两侧，普通箱子返回自身；非箱子容器返回空列表。 */
    private List<Block> getHolderBlocks(Inventory inventory) {
        List<Block> blocks = new ArrayList<>();
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            addChestBlock(blocks, doubleChest.getLeftSide());
            addChestBlock(blocks, doubleChest.getRightSide());
        } else if (holder instanceof Chest) {
            blocks.add(((Chest) holder).getBlock());
        }
        return blocks;
    }

    /** 双箱子一侧可能是 Chest 或 TrappedChest（均继承 Chest），仅收集箱子方块。 */
    private void addChestBlock(List<Block> blocks, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            blocks.add(chest.getBlock());
        }
    }

    /** 一次临时授权的记录（子类可继承携带额外恢复信息）。 */
    protected static class TempGrant {
        /** 是否实际执行了授权（关闭时需要恢复）；false 表示玩家原本已有权限（仅记录状态）。 */
        final boolean granted;

        protected TempGrant(boolean granted) {
            this.granted = granted;
        }
    }
}
