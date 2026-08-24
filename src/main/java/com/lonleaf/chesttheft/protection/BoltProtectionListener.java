package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
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
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.event.LockBlockEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bolt 保护插件集成（软依赖）：订阅保护创建事件（{@link LockBlockEvent}）自动卸锁；
 * 打开受 Bolt 保护箱子时临时授予访问权限并开启 NOSPAM，关闭容器/退出时恢复。
 */
public class BoltProtectionListener implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    /** 临时授权数据库记录（崩溃后启动清理恢复残留授权）。 */
    private final Database database;
    /** 保护创建回调（自动卸锁逻辑，由 ProtectionListener 提供）。 */
    private final Consumer<Block> protectionCreatedHandler;
    /** 临时授权记录：箱子位置 → 玩家 → 授权信息；玩家关闭容器或退出时恢复。 */
    private final Map<BlockLocation, Map<UUID, TempGrant>> temporaryAccesses = new HashMap<>();

    public BoltProtectionListener(Plugin plugin, PluginConfig config, Consumer<Block> protectionCreatedHandler, Database database) {
        this.plugin = plugin;
        this.config = config;
        this.protectionCreatedHandler = protectionCreatedHandler;
        this.database = database;
    }

    /** 订阅 Bolt 保护创建事件（仅 Bolt 插件存在时）。 */
    public void register() {
        if (Bukkit.getPluginManager().getPlugin("Bolt") != null) {
            BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
            if (bolt != null) {
                Object consumer = createBoltConsumer();
                bolt.registerListener(LockBlockEvent.class, (Consumer<LockBlockEvent>) consumer);
            }
        }
    }

    /**
     * 插件禁用时：撤销在线玩家持有的全部临时授权（Bolt access 为持久化写入，禁用/崩溃后不会自动消失），
     * 并删除对应 temp_grants 记录；Bolt 无取消订阅接口，无需注销监听器。
     */
    public void unregister() {
        if (!temporaryAccesses.isEmpty() && Bukkit.getPluginManager().getPlugin("Bolt") != null) {
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
        }
        temporaryAccesses.clear();
    }

    // ==================== 打开容器：临时授权 + 抑制提示 ====================

    /**
     * 打开上锁箱子前：临时授予 Bolt 打开权限（access normal）并开启 NOSPAM，
     * 使随后的 InventoryOpenEvent 不被拦截、不收到"被玩家锁定"提示；关闭容器或退出时恢复。
     * 用 findProtection 定位保护（与 Bolt 打开检查同一 matcher 逻辑，兼容双箱子合并保护）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestOpen(ChestOpenEvent event) {
        if (Bukkit.getPluginManager().getPlugin("Bolt") == null || event.getBlock() == null) {
            return;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            return;
        }
        org.popcraft.bolt.protection.Protection protection = bolt.findProtection(event.getBlock());
        if (protection == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        BlockLocation location = BlockLocation.from(event.getBlock());
        // 无论是否临时授权，打开期间均开启 NOSPAM：抑制 Bolt 对非所有者交互受保护方块发送的"被玩家锁定"提示
        boolean nospamBefore = toggleNospam(player, true);
        boolean granted = false;
        String original = null;
        // 玩家已有打开权限（如保护所有者）时无需写入 access，仅记录 NOSPAM 状态
        if (!bolt.canAccess(protection, player, "open")) {
            Map<String, String> access = protection.getAccess();
            String key = "player:" + uuid;
            original = access.get(key);
            // 先落库（崩溃保险）：记录授权前原值，启动清理时据此恢复；Bolt access 为持久化写入，
            // 崩溃后残留的临时授权会在下次启动按记录移除/恢复；落库失败则中止授权
            if (database.recordTempGrant("bolt", location, uuid, original == null ? "" : original)) {
                try {
                    access.put(key, "normal");
                    bolt.saveProtection(protection);
                    granted = true;
                } catch (Exception e) {
                    // 写入失败：删除记录避免留下无权限的无效记录
                    plugin.getLogger().fine("Bolt temp grant failed: " + e.getMessage());
                    try {
                        database.deleteTempGrant("bolt", location, uuid);
                    } catch (Exception ignored) {
                    }
                }
            } else {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_GRANT_RECORD_FAIL, "bolt", location));
            }
        }
        Map<UUID, TempGrant> grants = temporaryAccesses.computeIfAbsent(location, k -> new HashMap<>());
        if (grants.containsKey(uuid)) {
            // 上次打开被其他插件拦截未真正发生（无关闭事件撤销）：先恢复旧授权，再记录本次授权，避免覆盖原值
            revokeGrant(bolt, location, uuid, grants.remove(uuid));
        }
        grants.put(uuid, new TempGrant(granted, original, nospamBefore));
        if (config.isDebug()) {
            plugin.getLogger().info("临时授予 Bolt 打开权限: " + player.getName() + " @ " + location
                    + (granted ? "" : "（已有权限，仅抑制提示）"));
        }
    }

    /** 玩家关闭容器：撤销其在已关闭箱子上的临时 Bolt 访问授权（恢复原 access 值与 NOSPAM）。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (temporaryAccesses.isEmpty() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        List<Block> blocks = getHolderBlocks(event.getInventory());
        if (blocks.isEmpty()) {
            return;
        }
        revokeTemporaryAccess(player, blocks);
    }

    /** 玩家退出：撤销其全部临时 Bolt 访问授权，避免授权泄漏。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        revokeTemporaryAccess(event.getPlayer(), null);
    }

    /**
     * 撤销玩家在指定方块上的临时授权；blocks 为 null 时撤销该玩家的全部临时授权
     * （玩家退出场景，无 InventoryCloseEvent 兜底）。
     */
    private void revokeTemporaryAccess(Player player, List<Block> blocks) {
        if (temporaryAccesses.isEmpty() || Bukkit.getPluginManager().getPlugin("Bolt") == null) {
            return;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (blocks == null) {
            for (Map.Entry<BlockLocation, Map<UUID, TempGrant>> entry : new HashMap<>(temporaryAccesses).entrySet()) {
                TempGrant grant = entry.getValue().remove(uuid);
                if (entry.getValue().isEmpty()) {
                    temporaryAccesses.remove(entry.getKey());
                }
                if (grant != null) {
                    revokeGrant(bolt, entry.getKey(), uuid, grant);
                }
            }
            return;
        }
        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            BlockLocation location = BlockLocation.from(block);
            Map<UUID, TempGrant> grants = temporaryAccesses.get(location);
            if (grants == null) {
                continue;
            }
            TempGrant grant = grants.remove(uuid);
            if (grants.isEmpty()) {
                temporaryAccesses.remove(location);
            }
            if (grant != null) {
                revokeGrant(bolt, location, uuid, grant);
            }
        }
    }

    /** 撤销一次临时授权：恢复 access 原值（granted 时）并恢复 NOSPAM 状态。 */
    private void revokeGrant(BoltAPI bolt, BlockLocation location, UUID uuid, TempGrant grant) {
        if (grant.granted) {
            restoreAccess(bolt, location, uuid, grant.originalAccess);
            // 权限已恢复，删除数据库记录（崩溃清理不再处理该条）
            try {
                database.deleteTempGrant("bolt", location, uuid);
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
        }
        if (!grant.nospamBefore) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                toggleNospam(player, false);
            }
        }
        if (config.isDebug()) {
            plugin.getLogger().info("撤销临时 Bolt 打开权限: " + uuid + " @ " + location);
        }
    }

    // ==================== 崩溃残留清理 ====================

    /**
     * 插件启动时依据数据库记录清理崩溃残留的 Bolt 访问授权（access 为持久化写入，崩溃后不会自动消失）：
     * 逐条按记录恢复 access 原值，成功后删除记录；恢复失败的记录保留，下次启动重试。
     */
    public void cleanupStale() {
        List<TempGrantRecord> records;
        try {
            records = database.getTempGrants("bolt");
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_READ_FAIL, e.getMessage()));
            return;
        }
        if (records.isEmpty()) {
            return;
        }
        plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_FOUND, records.size(), "bolt"));
        for (TempGrantRecord record : records) {
            BlockLocation loc = record.getLocation();
            World world = loc.toWorld();
            if (world == null) {
                // 世界已不存在：对应保护数据随世界移除，直接清理记录
                try {
                    database.deleteTempGrant("bolt", loc, record.getPlayerUuid());
                } catch (Exception e) {
                    plugin.getLogger().fine("清理失效记录失败: " + e.getMessage());
                }
                continue;
            }
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            try {
                revokeFromRecord(block, record.getPlayerUuid(), record.getExtra());
                database.deleteTempGrant("bolt", loc, record.getPlayerUuid());
                plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_CLEANED, "bolt", loc));
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_CLEAN_FAIL, e.getMessage()));
            }
        }
    }

    /** 依据记录撤销崩溃残留的 Bolt 访问授权（玩家可能离线，用 UUID 级恢复）。 */
    private void revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (Bukkit.getPluginManager().getPlugin("Bolt") == null) {
            return;
        }
        BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
        if (bolt == null) {
            return;
        }
        // extra 为空串表示授权前原本无 access 记录（撤销即移除）；否则恢复原值
        restoreAccess(bolt, block, playerUuid, extra.isEmpty() ? null : extra);
    }

    /**
     * 开启/关闭玩家的 Bolt NOSPAM 模式并返回操作前状态；
     * 玩家原本已开启（nospamBefore=true）时不重复操作，关闭时也无需恢复。
     */
    private boolean toggleNospam(Player player, boolean enable) {
        if (Bukkit.getPluginManager().getPlugin("Bolt") == null) {
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

    /** 按位置恢复 access 原值（BlockLocation 反查方块）。 */
    private void restoreAccess(BoltAPI bolt, BlockLocation location, UUID uuid, String original) {
        if (location == null) {
            return;
        }
        World world = location.toWorld();
        if (world == null) {
            return;
        }
        restoreAccess(bolt, world.getBlockAt(location.getX(), location.getY(), location.getZ()), uuid, original);
    }

    /** 恢复玩家在指定方块上的 Bolt access 原值；original 为 null 表示原本无记录，移除授权。 */
    private void restoreAccess(BoltAPI bolt, Block block, UUID uuid, String original) {
        org.popcraft.bolt.protection.Protection protection = bolt.findProtection(block);
        if (protection == null) {
            return;
        }
        Map<String, String> access = protection.getAccess();
        String key = "player:" + uuid;
        String current = access.get(key);
        if (original == null) {
            if (current == null) {
                return;
            }
            access.remove(key);
        } else {
            access.put(key, original);
        }
        bolt.saveProtection(protection);
        if (config.isDebug()) {
            plugin.getLogger().info("撤销临时 Bolt 打开权限: " + uuid + " @ " + BlockLocation.from(block));
        }
    }

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
                    protectionCreatedHandler.accept(event.getBlock());
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
