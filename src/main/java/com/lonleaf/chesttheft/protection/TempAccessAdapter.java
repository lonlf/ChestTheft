package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.database.TempGrantRecord;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 领地类 / WorldGuard / NoBuildPlus 保护适配器基类：实现统一的"打开容器事件内临时授权"流程——
 * 先撤销残留授权 → 判定（{@link #prepare}）→ 落库（仅持久化型）→ 写入（{@link #apply}）→ 记录。
 * 授权在玩家交互事件链内同步完成（早于保护插件 NORMAL 检查），收回由
 * {@link ProtectionManager} 在打开动作完成后调度执行（下一 tick）。
 * 持久化型保护（Bolt/LWC/领地/区域，写入保护插件自身存储）授权前落库，崩溃后启动清理恢复残留；
 * 附件型保护（{@link #persistGrant} 返回 false）不落库，重启内存重置即清。
 * 子类仅需实现各插件的判定（{@link #prepare}）、写入（{@link #apply}）、恢复（{@link #revoke}）
 * 与序列化（{@link #serializeGrant} / {@link #revokeFromRecord}）细节。
 */
public abstract class TempAccessAdapter implements ProtectionAdapter {

    protected final Plugin plugin;
    /** 临时授权数据库（崩溃残留清理用）；null 表示不落库（附件型保护）。 */
    protected final Database database;
    /** 待收回的临时授权记录：箱子位置 → 玩家 → 授权信息；收回（revokeAccess）或插件禁用时恢复。 */
    private final Map<BlockLocation, Map<UUID, TempGrant>> temporaryGrants = new HashMap<>();
    /** 保护创建回调（自动卸锁逻辑，由 ProtectionManager 提供；无领地创建订阅的适配器不使用）。 */
    protected Consumer<Block> protectionCreatedHandler;

    /** 不落库构造（附件型保护使用）。 */
    protected TempAccessAdapter(Plugin plugin) {
        this(plugin, null);
    }

    /** 落库构造（持久化型保护使用）。 */
    protected TempAccessAdapter(Plugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    // ==================== 判定与写入（子类实现） ====================

    /** 打开容器前的只读检查：计算将要授予的权限信息（不执行任何写入）；返回 null 表示无需授权。 */
    protected abstract TempGrant prepare(Block block, Player player);

    /** 执行临时授权写入。仅在 {@link #prepare} 返回非 null 后调用；写入失败应自行回滚。 */
    protected abstract void apply(Block block, Player player, TempGrant grant);

    /** 撤销一次临时授权（恢复原状）。 */
    protected abstract void revoke(Block block, Player player, TempGrant grant);

    /** 序列化授权前原状态（供崩溃后启动清理恢复）；仅 {@link #persistGrant} 返回 true 时调用。 */
    protected abstract String serializeGrant(TempGrant grant);

    /** 依据数据库记录撤销崩溃残留的授权（玩家可能离线，用 UUID 级恢复；extra 为 {@link #serializeGrant} 序列化值）。 */
    protected abstract void revokeFromRecord(Block block, UUID playerUuid, String extra);

    /** 本次授权是否需要落库（崩溃残留清理）；附件型保护（权限重启即清）返回 false。 */
    protected boolean persistGrant(TempGrant grant) {
        return true;
    }

    // ==================== 统一授权流程 ====================

    @Override
    public void grantOpenAccess(Block block, Player player) {
        if (block == null || !isActive()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        BlockLocation location = BlockLocation.from(block);
        // 1. 先撤销上次残留授权（上次打开被其他插件拦截未真正发生，无收回时机），再重新判定：
        //    若先判定后撤销，旧授权被撤销后本次判定结果将失效（玩家失去访问权限）
        Map<UUID, TempGrant> grants = temporaryGrants.get(location);
        TempGrant previous = grants == null ? null : grants.remove(uuid);
        if (previous != null) {
            revokeGrant(block, player, previous);
            if (grants.isEmpty()) {
                temporaryGrants.remove(location);
            }
        }
        // 2. 判定是否需要授权（玩家已有权限时返回 null，不授权不记录）
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
        // 3. 持久化型保护先落库（崩溃保险）：记录授权前原状态，启动清理时据此恢复；
        //    落库失败则中止授权，避免"已授权但无记录"导致残留权限无法清理
        if (persistGrant(grant) && database != null) {
            if (!database.recordTempGrant(pluginType(), location, uuid, serializeGrant(grant))) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_GRANT_RECORD_FAIL, pluginType(), location));
                return;
            }
        }
        // 4. 执行授权写入（事件内瞬时生效，早于保护插件 NORMAL 检查）
        try {
            apply(block, player, grant);
        } catch (Exception e) {
            // 授权写入失败：删除记录避免留下无权限的无效记录，中止授权
            plugin.getLogger().fine("临时授权写入失败: " + e.getMessage());
            if (persistGrant(grant) && database != null) {
                try {
                    database.deleteTempGrant(pluginType(), location, uuid);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        // 5. 记录本次授权（收回/禁用时据此撤销）
        temporaryGrants.computeIfAbsent(location, k -> new HashMap<>()).put(uuid, grant);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("临时授予容器打开权限: " + player.getName() + " @ " + location);
        }
    }

    @Override
    public void revokeAccess(Block block, Player player) {
        if (block == null || temporaryGrants.isEmpty()) {
            return;
        }
        BlockLocation location = BlockLocation.from(block);
        Map<UUID, TempGrant> grants = temporaryGrants.get(location);
        if (grants == null) {
            return;
        }
        TempGrant grant = grants.remove(player.getUniqueId());
        if (grants.isEmpty()) {
            temporaryGrants.remove(location);
        }
        if (grant != null) {
            revokeGrant(block, player, grant);
        }
    }

    /** 撤销一次临时授权（异常隔离），并删除对应数据库记录。 */
    private void revokeGrant(Block block, Player player, TempGrant grant) {
        try {
            revoke(block, player, grant);
        } catch (Exception e) {
            plugin.getLogger().fine("撤销临时授权失败: " + e.getMessage());
        }
        if (persistGrant(grant) && database != null) {
            try {
                database.deleteTempGrant(pluginType(), BlockLocation.from(block), player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
        }
    }

    /** 按位置反查方块后撤销授权（插件禁用兜底场景）。 */
    private void revokeGrantByLocation(BlockLocation location, Player player, TempGrant grant) {
        World world = location.toWorld();
        if (world == null) {
            return;
        }
        revokeGrant(world.getBlockAt(location.getX(), location.getY(), location.getZ()), player, grant);
    }

    // ==================== 生命周期 ====================

    /** 默认注册实现：仅保存保护创建回调（无领地创建订阅的适配器无需重写）。 */
    @Override
    public void register(Consumer<Block> protectionCreatedHandler) {
        this.protectionCreatedHandler = protectionCreatedHandler;
    }

    @Override
    public void unregister() {
        cleanup();
    }

    /** 插件禁用时清理全部待收回的临时授权（避免授权泄漏；事件内授权窗口仅数 tick，残留面极小）。 */
    @Override
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

    /** 启动时依据数据库记录清理崩溃残留的临时授权（仅持久化型）：逐条恢复原状态，成功后删除记录。 */
    @Override
    public void cleanupStale() {
        if (database == null) {
            return;
        }
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

    /** 一次临时授权的记录（子类可继承携带额外恢复信息）。 */
    protected static class TempGrant {
        /** 是否实际执行了授权（收回时需要恢复）；false 表示玩家原本已有权限（仅记录状态）。 */
        final boolean granted;

        protected TempGrant(boolean granted) {
            this.granted = granted;
        }
    }
}
