package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.database.TempGrantRecord;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.event.LockBlockEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bolt 保护插件适配器：订阅保护创建事件（{@link LockBlockEvent}）自动卸锁；
 * 打开受 Bolt 保护箱子时事件内临时授予访问权限并开启 NOSPAM，收回（下一 tick）时恢复。
 * Bolt access 为持久化写入（Bolt 库），授权前落库（记录原值），崩溃后启动清理恢复残留。
 */
public class BoltAdapter implements ProtectionAdapter {

    /** 临时授权写入的 Bolt access 值（同时作为识别"残留临时值"的哨兵）。 */
    private static final String TEMP_ACCESS_VALUE = "normal";

    private final Plugin plugin;
    private final PluginConfig config;
    /** 临时授权数据库记录（崩溃后启动清理恢复残留授权）。 */
    private final Database database;
    /** 保护创建回调（自动卸锁逻辑，由 ProtectionManager 提供）。 */
    private Consumer<Block> protectionCreatedHandler;
    /** 待收回的临时授权记录：箱子位置 → 玩家 → 授权信息；收回或禁用时恢复。 */
    private final Map<BlockLocation, Map<UUID, TempGrant>> temporaryAccesses = new HashMap<>();

    public BoltAdapter(Plugin plugin, PluginConfig config, Database database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    @Override
    public String pluginType() {
        return "bolt";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("Bolt") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isBoltProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.boltOwner(block);
    }

    // ==================== 打开容器：临时授权 + 抑制提示 ====================

    /**
     * 打开上锁箱子前：临时授予 Bolt 打开权限（access normal）并开启 NOSPAM，
     * 使随后的打开不被拦截、不收到"被玩家锁定"提示；关闭容器或退出时恢复。
     * 用 findProtection 定位保护（与 Bolt 打开检查同一 matcher 逻辑，兼容双箱子合并保护）。
     */
    @Override
    public void grantOpenAccess(Block block, Player player) {
        if (!isActive() || block == null) {
            return;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            return;
        }
        org.popcraft.bolt.protection.Protection protection = bolt.findProtection(block);
        if (protection == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        BlockLocation location = BlockLocation.from(block);
        // 1. 先撤销上次残留授权（上次打开被拦截未真正发生，无关闭事件撤销），再重新判定授权：
        //    若先判定后撤销，旧授权被撤销后本次判定结果将失效（玩家失去访问权限，首次打不开）
        Map<UUID, TempGrant> grants = temporaryAccesses.get(location);
        TempGrant previous = grants == null ? null : grants.remove(uuid);
        if (previous != null) {
            if (!revokeGrant(bolt, location, uuid, previous)) {
                // 上一次撤销失败：回填记录并放弃本次授权。
                // 否则残留的临时值会被下面当成"原值"记录，最终恢复成永久授权且记录看起来正常
                temporaryAccesses.computeIfAbsent(location, k -> new HashMap<>()).put(uuid, previous);
                if (config.isDebug()) {
                    plugin.getLogger().info("Bolt 上次临时授权撤销失败，跳过本次授权: " + uuid + " @ " + location);
                }
                return;
            }
            if (grants.isEmpty()) {
                temporaryAccesses.remove(location);
            }
        }
        // 2. 打开期间开启 NOSPAM：抑制 Bolt 对非所有者交互受保护方块发送的"被玩家锁定"提示
        boolean nospamBefore = toggleNospam(player, true);
        // 3. 玩家已有打开权限（如保护所有者）时无需写入 access，仅记录 NOSPAM 状态
        boolean granted = false;
        String original = null;
        if (!bolt.canAccess(protection, player, "open")) {
            Map<String, String> access = protection.getAccess();
            String key = "player:" + uuid;
            original = access.get(key);
            if (TEMP_ACCESS_VALUE.equals(original)) {
                // 读到的"原值"是本插件写入的临时值（历史残留）：只告警便于排查。
                // 不改行为——"normal"也可能是所有者显式授予的合法值，按原值恢复更安全；
                // 新的残留不会再产生（撤销失败会回填记录并中止本次授权）
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_STALE_VALUE_SEEN, "bolt", location));
            }
            // 先落库（崩溃保险）：记录授权前原值，启动清理时据此恢复；Bolt access 为持久化写入，
            // 崩溃后残留的临时授权会在下次启动按记录移除/恢复；落库失败则中止授权
            if (!database.recordTempGrant(pluginType(), location, uuid, original == null ? "" : original)) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_GRANT_RECORD_FAIL, pluginType(), location));
            } else {
                try {
                    access.put(key, TEMP_ACCESS_VALUE);
                    bolt.saveProtection(protection);
                    granted = true;
                } catch (Exception e) {
                    // 写入失败：回滚已写入的 access；回滚成功才删记录（无权限无记录），
                    // 回滚失败则保留记录并标记已授权，交给撤销/启动清理重试
                    plugin.getLogger().fine("Bolt temp grant failed: " + e.getMessage());
                    if (rollbackAccess(bolt, protection, key, original)) {
                        granted = false;
                        try {
                            database.deleteTempGrant(pluginType(), location, uuid);
                        } catch (Exception ignored) {
                        }
                    } else {
                        granted = true;
                        plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_STALE_KEEP,
                                pluginType(), location));
                    }
                }
            }
        }
        // 4. 记录本次授权状态（关闭/退出时据此恢复）
        temporaryAccesses.computeIfAbsent(location, k -> new HashMap<>())
                .put(uuid, new TempGrant(granted, original, nospamBefore));
        if (config.isDebug()) {
            plugin.getLogger().info("临时授予 Bolt 打开权限: " + player.getName() + " @ " + location
                    + (granted ? "" : "（已有权限，仅抑制提示）"));
        }
    }

    @Override
    public void revokeAccess(Block block, Player player) {
        if (block == null || temporaryAccesses.isEmpty() || !isActive()) {
            return;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            return;
        }
        BlockLocation location = BlockLocation.from(block);
        Map<UUID, TempGrant> grants = temporaryAccesses.get(location);
        if (grants == null) {
            return;
        }
        TempGrant grant = grants.remove(player.getUniqueId());
        if (grants.isEmpty()) {
            temporaryAccesses.remove(location);
        }
        if (grant != null && !revokeGrant(bolt, location, player.getUniqueId(), grant)) {
            // 撤销失败（如区块未加载无法访问）：恢复记录供下次授权/插件禁用清理时重试
            temporaryAccesses.computeIfAbsent(location, k -> new HashMap<>()).put(player.getUniqueId(), grant);
        }
    }

    /** 回滚刚写入的 access 并回读确认：真正恢复成功才返回 true。 */
    private boolean rollbackAccess(BoltAPI bolt, org.popcraft.bolt.protection.Protection protection,
                                   String key, String original) {
        try {
            if (original == null) {
                protection.getAccess().remove(key);
            } else {
                protection.getAccess().put(key, original);
            }
            bolt.saveProtection(protection);
            String current = protection.getAccess().get(key);
            return original == null ? current == null : original.equals(current);
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_REVOKE_FAIL, e.getMessage()));
            return false;
        }
    }

    /** 撤销一次临时授权：恢复 access 原值（granted 时）并恢复 NOSPAM 状态；
     *  撤销成功才删除数据库记录；恢复失败（区块未加载等）返回 false，记录保留供启动清理兜底。 */
    private boolean revokeGrant(BoltAPI bolt, BlockLocation location, UUID uuid, TempGrant grant) {
        boolean restored = !grant.granted;
        if (grant.granted) {
            // 第三方 API 异常隔离：失败返回 false（记录保留、上层回填），不向授权/撤销循环冒泡
            try {
                restored = restoreAccess(bolt, location, uuid, grant.originalAccess);
            } catch (LinkageError | Exception e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_REVOKE_FAIL, e.getMessage()));
                restored = false;
            }
        }
        if (restored) {
            // 权限已恢复，删除数据库记录（崩溃清理不再处理该条）
            try {
                database.deleteTempGrant(pluginType(), location, uuid);
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
        }
        if (!grant.nospamBefore) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                try {
                    toggleNospam(player, false);
                } catch (LinkageError | Exception e) {
                    plugin.getLogger().fine("恢复 NOSPAM 状态失败: " + e.getMessage());
                }
            }
        }
        if (config.isDebug()) {
            plugin.getLogger().info("撤销临时 Bolt 打开权限: " + uuid + " @ " + location
                    + (restored ? "" : "（恢复失败，保留记录待重试）"));
        }
        return restored;
    }

    // ==================== 生命周期 ====================

    /** 订阅 Bolt 保护创建事件（仅 Bolt 插件存在时）。 */
    @Override
    public void register(Consumer<Block> protectionCreatedHandler) {
        this.protectionCreatedHandler = protectionCreatedHandler;
        if (isActive()) {
            BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
            if (bolt != null) {
                Object consumer = createBoltConsumer();
                bolt.registerListener(LockBlockEvent.class, (Consumer<LockBlockEvent>) consumer);
            }
        }
    }

    /**
     * 插件禁用时：撤销在线玩家持有的全部待收回临时授权（Bolt access 为持久化写入，
     * 禁用时残留不会自动消失，必须主动恢复）；Bolt 无取消订阅接口，无需注销监听器。
     */
    @Override
    public void unregister() {
        cleanup();
    }

    /** 插件禁用时清理全部待收回临时授权（撤销 access 与 NOSPAM 状态）。 */
    @Override
    public void cleanup() {
        if (temporaryAccesses.isEmpty() || !isActive()) {
            temporaryAccesses.clear();
            return;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt != null) {
            for (Map.Entry<BlockLocation, Map<UUID, TempGrant>> entry : new HashMap<>(temporaryAccesses).entrySet()) {
                for (Map.Entry<UUID, TempGrant> playerEntry : new HashMap<>(entry.getValue()).entrySet()) {
                    Player player = Bukkit.getPlayer(playerEntry.getKey());
                    if (player != null && player.isOnline()) {
                        revokeGrant(bolt, entry.getKey(), playerEntry.getKey(), playerEntry.getValue());
                    }
                }
            }
        }
        temporaryAccesses.clear();
    }

    /**
     * 插件启动时依据数据库记录清理崩溃残留的 Bolt 访问授权（access 为持久化写入，崩溃后不会自动消失）：
     * 逐条按记录恢复 access 原值，成功后删除记录；恢复失败的记录保留，下次启动重试。
     */
    @Override
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
                if (revokeFromRecord(block, record.getPlayerUuid(), record.getExtra())) {
                    database.deleteTempGrant(pluginType(), loc, record.getPlayerUuid());
                    plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_CLEANED, pluginType(), loc));
                } else {
                    // 恢复未确认成功（保护已消失/区块未加载）：保留记录下次启动重试，避免授权残留无法追踪
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_STALE_KEEP, pluginType(), loc));
                }
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_CLEAN_FAIL, e.getMessage()));
            }
        }
    }

    /** 依据记录撤销崩溃残留的 Bolt 访问授权（玩家可能离线，用 UUID 级恢复）；恢复成功返回 true。 */
    private boolean revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (!isActive()) {
            return false;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            return false;
        }
        // extra 为空串表示授权前原本无 access 记录（撤销即移除）；否则恢复原值
        return restoreAccess(bolt, block, playerUuid, extra.isEmpty() ? null : extra);
    }

    // ==================== Bolt 内部工具 ====================

    /**
     * 开启/关闭玩家的 Bolt NOSPAM 模式并返回操作前状态；
     * 玩家原本已开启（nospamBefore=true）时不重复操作，关闭时也无需恢复。
     */
    private boolean toggleNospam(Player player, boolean enable) {
        if (!isActive()) {
            return true;
        }
        org.popcraft.bolt.util.BoltPlayer boltPlayer =
                ((org.popcraft.bolt.BoltPlugin) Bukkit.getPluginManager().getPlugin("Bolt")).player(player);
        boolean before = boltPlayer.hasMode(org.popcraft.bolt.util.Mode.NOSPAM);
        if (before != enable) {
            boltPlayer.toggleMode(org.popcraft.bolt.util.Mode.NOSPAM);
        }
        return before;
    }

    /** 按位置恢复 access 原值（BlockLocation 反查方块）；无法访问（区块未加载）时返回 false。 */
    private boolean restoreAccess(BoltAPI bolt, BlockLocation location, UUID uuid, String original) {
        if (location == null) {
            return false;
        }
        World world = location.toWorld();
        if (world == null) {
            return false;
        }
        return restoreAccess(bolt, world.getBlockAt(location.getX(), location.getY(), location.getZ()), uuid, original);
    }

    /** 恢复玩家在指定方块上的 Bolt access 原值；original 为 null 表示原本无记录，移除授权。
     *  保护已消失时返回 false（视为无法恢复）。 */
    private boolean restoreAccess(BoltAPI bolt, Block block, UUID uuid, String original) {
        org.popcraft.bolt.protection.Protection protection = bolt.findProtection(block);
        if (protection == null) {
            return false;
        }
        Map<String, String> access = protection.getAccess();
        String key = "player:" + uuid;
        String current = access.get(key);
        if (original == null) {
            if (current == null) {
                return true;   // 本就没有该玩家的授权，无需恢复
            }
            access.remove(key);
        } else {
            access.put(key, original);
        }
        bolt.saveProtection(protection);
        if (config.isDebug()) {
            plugin.getLogger().info("撤销临时 Bolt 打开权限: " + uuid + " @ " + BlockLocation.from(block));
        }
        return true;
    }

    // ==================== Bolt 保护创建回调 ====================

    /**
     * 创建 Bolt 保护创建事件的监听器。
     * 返回 Object 而非 Consumer（方法签名在类验证期解析，未装 Bolt 时会导致类加载失败；lambda 同理）；
     * 仅在 Bolt 插件存在时调用，此时 Bolt 类必已加载。
     */
    private Object createBoltConsumer() {
        return new Consumer<LockBlockEvent>() {
            @Override
            public void accept(LockBlockEvent event) {
                try {
                    // Bolt 事件回调线程不保证为主线程：自动卸锁涉及 DB 写与掉落物，必须调度回主线程执行
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> protectionCreatedHandler.accept(event.getBlock()));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_PROTECTION_CALLBACK_FAIL, "Bolt", e.getMessage()));
                }
            }
        };
    }

    /** 一次临时授权的完整信息：是否写入了 access、原 access 值、授权前玩家的 NOSPAM 状态。 */
    private static final class TempGrant {
        /** 是否写入了临时 access 授权（需在关闭时恢复）；false 表示仅开启了 NOSPAM。 */
        final boolean granted;
        /** 玩家在保护 access 中的原值；granted 且为 null 表示原本无记录（关闭时移除授权）。 */
        final String originalAccess;
        /** 授权前玩家是否已开启 Bolt NOSPAM 模式；已开启则关闭时无需恢复。 */
        final boolean nospamBefore;

        TempGrant(boolean granted, String originalAccess, boolean nospamBefore) {
            this.granted = granted;
            this.originalAccess = originalAccess;
            this.nospamBefore = nospamBefore;
        }
    }
}
