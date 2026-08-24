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
import java.util.function.Consumer;

/**
 * LWC 保护插件集成（软依赖）：通过 ModuleLoader 脚本模块订阅保护注册后回调（onPostRegistration），
 * 已上锁箱子被 LWC 保护且关闭撬锁时自动卸锁（逻辑由 {@link ProtectionListener} 提供）。
 * 同时监听 {@link ChestOpenEvent} 临时授予玩家 LWC 访问权限，避免非所有者在撬锁成功后的授权窗口内
 * 被 LWC 拦截打开容器。
 */
public class LwcProtectionListener implements Listener {

    private final Plugin plugin;
    /** 临时授权数据库记录（崩溃后启动清理恢复残留授权）。 */
    private final Database database;
    /** 保护创建回调（自动卸锁逻辑，由 ProtectionListener 提供）。 */
    private final Consumer<Block> protectionCreatedHandler;
    /** LWC 实例引用（Object 持有，避免字段类型解析 LWC 类）。 */
    private Object lwc;
    /** 临时授权记录：箱子位置 → 玩家 → 是否由本模块添加了临时权限。 */
    private final Map<BlockLocation, Map<UUID, Boolean>> temporaryGrants = new HashMap<>();

    public LwcProtectionListener(Plugin plugin, Consumer<Block> protectionCreatedHandler, Database database) {
        this.plugin = plugin;
        this.protectionCreatedHandler = protectionCreatedHandler;
        this.database = database;
    }

    /** 订阅 LWC 保护注册后回调（仅 LWC 插件存在时）。 */
    public void register() {
        Object lwcPlugin = Bukkit.getPluginManager().getPlugin("LWC");
        if (lwcPlugin != null) {
            lwc = ((com.griefcraft.lwc.LWCPlugin) lwcPlugin).getLWC();
            Object module = createLwcModule();
            ((com.griefcraft.lwc.LWC) lwc).getModuleLoader().registerModule(plugin, (com.griefcraft.scripting.Module) module);
        }
    }

    /** 注销监听（插件禁用时调用）：先撤销在线玩家的临时授权，再按插件移除模块。 */
    public void unregister() {
        revokeAllTemporary();
        if (lwc != null) {
            try {
                ((com.griefcraft.lwc.LWC) lwc).getModuleLoader().removeModules(plugin);
            } catch (Exception e) {
                // LWC 已随禁用流程卸载时忽略
            }
            lwc = null;
        }
        temporaryGrants.clear();
    }

    /** 撤销全部在线玩家当前持有的临时 LWC 访问授权（禁用前调用，避免授权泄漏到下次启用）。 */
    private void revokeAllTemporary() {
        if (lwc == null || temporaryGrants.isEmpty()) {
            return;
        }
        for (Map.Entry<BlockLocation, Map<UUID, Boolean>> entry : new HashMap<>(temporaryGrants).entrySet()) {
            for (Map.Entry<UUID, Boolean> playerEntry : new HashMap<>(entry.getValue()).entrySet()) {
                if (Boolean.TRUE.equals(playerEntry.getValue())) {
                    Player player = Bukkit.getPlayer(playerEntry.getKey());
                    if (player != null && player.isOnline()) {
                        revokeGrantByLocation(entry.getKey(), playerEntry.getKey());
                    }
                }
            }
        }
    }

    // ==================== 打开容器：临时授权 LWC 访问权限 ====================

    /**
     * 打开上锁箱子前：临时授予 LWC 访问权限（添加 volatile Permission），
     * 使随后的容器打开不被 LWC 拦截。关闭容器或退出时撤销授权。
     * 用 findProtection 定位 LWC 保护（兼容双箱子合并保护）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestOpen(ChestOpenEvent event) {
        if (lwc == null || event.getBlock() == null) {
            return;
        }
        Block block = event.getBlock();
        com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
        com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
        if (protection == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        BlockLocation location = BlockLocation.from(block);
        String playerName = player.getName();

        boolean granted = false;
        // 检查玩家是否已有 PLAYER 级访问权限
        if (protection.getAccess(playerName, com.griefcraft.model.Permission.Type.PLAYER)
                != com.griefcraft.model.Permission.Access.PLAYER) {
            // 先落库（崩溃保险）：LWC 的 volatile 权限在插件禁用/服务器崩溃重启时会残留，
            // 启动清理时按记录移除这些临时权限；落库失败则中止授权
            if (!database.recordTempGrant("lwc", location, uuid, "")) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_GRANT_RECORD_FAIL, "lwc", location));
            } else {
                try {
                    com.griefcraft.model.Permission tempPerm = new com.griefcraft.model.Permission(
                            playerName, com.griefcraft.model.Permission.Type.PLAYER,
                            com.griefcraft.model.Permission.Access.PLAYER);
                    tempPerm.setVolatile(true);
                    protection.addPermission(tempPerm);
                    protection.save();
                    granted = true;
                } catch (Exception e) {
                    plugin.getLogger().fine("LWC temp grant failed: " + e.getMessage());
                    // 授权写入失败：删除记录避免留下无权限的无效记录
                    try {
                        database.deleteTempGrant("lwc", location, uuid);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // 记录授权状态（无论是否已授权，均用于 close 时清理）
        Map<UUID, Boolean> grants = temporaryGrants.computeIfAbsent(location, k -> new HashMap<>());
        if (grants.containsKey(uuid)) {
            // 上次打开被其他插件拦截未真正发生（无关闭事件撤销）：先恢复旧授权，再记录本次授权
            revokeGrant(block, uuid, grants.remove(uuid));
        }
        grants.put(uuid, granted);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("临时授予 LWC 打开权限: " + player.getName() + " @ " + location
                    + (granted ? "" : "（已有权限，仅记录状态）"));
        }
    }

    /** 玩家关闭容器：撤销其在已关闭箱子上的临时 LWC 访问授权。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (lwc == null || temporaryGrants.isEmpty() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        List<Block> blocks = getHolderBlocks(event.getInventory());
        if (blocks.isEmpty()) {
            return;
        }
        revokeTemporaryAccess(player, blocks);
    }

    /** 玩家退出：撤销其全部临时 LWC 访问授权，避免授权泄漏。 */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (lwc == null) {
            return;
        }
        revokeTemporaryAccess(event.getPlayer(), null);
    }

    /**
     * 撤销玩家在指定方块上的临时授权；blocks 为 null 时撤销该玩家的全部临时授权。
     */
    private void revokeTemporaryAccess(Player player, List<Block> blocks) {
        if (lwc == null || temporaryGrants.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (blocks == null) {
            for (Map.Entry<BlockLocation, Map<UUID, Boolean>> entry : new HashMap<>(temporaryGrants).entrySet()) {
                Boolean granted = entry.getValue().remove(uuid);
                if (entry.getValue().isEmpty()) {
                    temporaryGrants.remove(entry.getKey());
                }
                if (granted != null && granted) {
                    revokeGrantByLocation(entry.getKey(), uuid);
                }
            }
            return;
        }
        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            BlockLocation location = BlockLocation.from(block);
            Map<UUID, Boolean> grants = temporaryGrants.get(location);
            if (grants == null) {
                continue;
            }
            Boolean granted = grants.remove(uuid);
            if (grants.isEmpty()) {
                temporaryGrants.remove(location);
            }
            if (granted != null && granted) {
                revokeGrantByLocation(location, uuid);
            }
        }
    }

    /** 按位置与 UUID 撤销临时授权。 */
    private void revokeGrantByLocation(BlockLocation location, UUID uuid) {
        if (lwc == null) {
            return;
        }
        try {
            World world = location.toWorld();
            if (world == null) {
                return;
            }
            Block block = world.getBlockAt(location.getX(), location.getY(), location.getZ());
            com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
            com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
            if (protection == null) {
                return;
            }
            // 撤销所有临时（volatile）权限
            protection.removeTemporaryPermissions();
            protection.save();
            // 权限已撤销，删除数据库记录（崩溃清理不再处理该条）
            try {
                database.deleteTempGrant("lwc", location, uuid);
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().fine("LWC temp revoke failed: " + e.getMessage());
        }
    }

    /** 撤销单次临时授权记录。 */
    private void revokeGrant(Block block, UUID uuid, boolean granted) {
        if (!granted) {
            return;
        }
        if (lwc == null) {
            return;
        }
        try {
            com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
            com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
            if (protection == null) {
                return;
            }
            protection.removeTemporaryPermissions();
            protection.save();
            // 权限已撤销，删除数据库记录（崩溃清理不再处理该条）
            try {
                database.deleteTempGrant("lwc", BlockLocation.from(block), uuid);
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().fine("LWC temp revoke failed: " + e.getMessage());
        }
    }

    // ==================== 崩溃残留清理 ====================

    /**
     * 插件启动时依据数据库记录清理崩溃残留的 LWC 临时授权（volatile 权限在插件禁用或服务器重启时不会自动移除）：
     * 逐条按记录移除保护上的所有临时权限，成功后删除记录；恢复失败的记录保留，下次启动重试。
     * 需在 {@link #register()} 之后调用（依赖已初始化的 LWC 实例）。
     */
    public void cleanupStale() {
        if (lwc == null) {
            return;
        }
        List<TempGrantRecord> records;
        try {
            records = database.getTempGrants("lwc");
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_READ_FAIL, e.getMessage()));
            return;
        }
        if (records.isEmpty()) {
            return;
        }
        plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_FOUND, records.size(), "lwc"));
        for (TempGrantRecord record : records) {
            BlockLocation loc = record.getLocation();
            World world = loc.toWorld();
            if (world == null) {
                // 世界已不存在：对应保护数据随世界移除，直接清理记录
                try {
                    database.deleteTempGrant("lwc", loc, record.getPlayerUuid());
                } catch (Exception e) {
                    plugin.getLogger().fine("清理失效记录失败: " + e.getMessage());
                }
                continue;
            }
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            try {
                revokeFromRecord(block);
                database.deleteTempGrant("lwc", loc, record.getPlayerUuid());
                plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_CLEANED, "lwc", loc));
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_CLEAN_FAIL, e.getMessage()));
            }
        }
    }

    /** 依据记录撤销崩溃残留的 LWC 临时授权：移除保护上的全部临时（volatile）权限。 */
    private void revokeFromRecord(Block block) {
        com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
        com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
        if (protection == null) {
            return;
        }
        protection.removeTemporaryPermissions();
        protection.save();
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

    // ==================== LWC 保护创建回调 ====================

    /**
     * 创建 LWC 脚本模块：仅保护注册后回调有逻辑，其余方法空实现。
     * 返回 Object 而非 Module（方法签名在类验证期解析，未装 LWC 时会导致类加载失败）；
     * 仅在 LWC 插件存在时调用，此时 LWC 类必已加载。
     */
    private Object createLwcModule() {
        return new com.griefcraft.scripting.Module() {
            @Override
            public void load(com.griefcraft.lwc.LWC lwc) {
            }

            @Override
            public void onReload(com.griefcraft.scripting.event.LWCReloadEvent event) {
            }

            @Override
            public void onAccessRequest(com.griefcraft.scripting.event.LWCAccessEvent event) {
            }

            @Override
            public void onDropItem(com.griefcraft.scripting.event.LWCDropItemEvent event) {
            }

            @Override
            public void onCommand(com.griefcraft.scripting.event.LWCCommandEvent event) {
            }

            @Override
            public void onRedstone(com.griefcraft.scripting.event.LWCRedstoneEvent event) {
            }

            @Override
            public void onDestroyProtection(com.griefcraft.scripting.event.LWCProtectionDestroyEvent event) {
            }

            @Override
            public void onProtectionInteract(com.griefcraft.scripting.event.LWCProtectionInteractEvent event) {
            }

            @Override
            public void onBlockInteract(com.griefcraft.scripting.event.LWCBlockInteractEvent event) {
            }

            @Override
            public void onEntityInteract(com.griefcraft.scripting.event.LWCEntityInteractEvent event) {
            }

            @Override
            public void onRegisterProtection(com.griefcraft.scripting.event.LWCProtectionRegisterEvent event) {
            }

            @Override
            public void onEntityInteractProtection(com.griefcraft.scripting.event.LWCProtectionInteractEntityEvent event) {
            }

            @Override
            public void onPostRegistration(com.griefcraft.scripting.event.LWCProtectionRegistrationPostEvent event) {
                try {
                    com.griefcraft.model.Protection protection = event.getProtection();
                    if (protection != null) {
                        protectionCreatedHandler.accept(protection.getBlock());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_PROTECTION_CALLBACK_FAIL, "LWC", e.getMessage()));
                }
            }

            @Override
            public void onPostRemoval(com.griefcraft.scripting.event.LWCProtectionRemovePostEvent event) {
            }

            @Override
            public void onSendLocale(com.griefcraft.scripting.event.LWCSendLocaleEvent event) {
            }

            @Override
            public void onMagnetPull(com.griefcraft.scripting.event.LWCMagnetPullEvent event) {
            }

            @Override
            public void onRegisterEntity(com.griefcraft.scripting.event.LWCProtectionRegisterEntityEvent event) {
            }
        };
    }
}