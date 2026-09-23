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
 * LWC 保护插件适配器：通过 ModuleLoader 脚本模块订阅保护注册后回调自动卸锁（逻辑由
 * {@link ProtectionManager} 提供）。打开受 LWC 保护的箱子时事件内临时授予玩家访问权限
 * （volatile Permission，持久化写入 LWC 库），收回（下一 tick）时移除；授权前落库，崩溃后启动清理。
 */
public class LwcAdapter implements ProtectionAdapter {

    private final Plugin plugin;
    /** 临时授权数据库记录（崩溃后启动清理恢复残留授权）。 */
    private final Database database;
    /** 保护创建回调（自动卸锁逻辑，由 ProtectionManager 提供）。 */
    private Consumer<Block> protectionCreatedHandler;
    /** LWC 实例引用（Object 持有，避免字段类型解析 LWC 类）。 */
    private Object lwc;
    /** 待收回的临时授权记录：箱子位置 → 玩家 → 是否由本模块添加了临时权限。 */
    private final Map<BlockLocation, Map<UUID, Boolean>> temporaryGrants = new HashMap<>();

    public LwcAdapter(Plugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String pluginType() {
        return "lwc";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("LWC") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isLWCProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.lwcOwner(block);
    }

    // ==================== 打开容器：临时授权 LWC 访问权限 ====================

    /**
     * 打开上锁箱子前：临时授予 LWC 访问权限（添加 volatile Permission），
     * 使随后的容器打开不被 LWC 拦截。关闭容器或退出时撤销授权。
     * 用 findProtection 定位 LWC 保护（兼容双箱子合并保护）。
     */
    @Override
    public void grantOpenAccess(Block block, Player player) {
        if (lwc == null || block == null) {
            return;
        }
        com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
        com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
        if (protection == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        BlockLocation location = BlockLocation.from(block);
        String playerName = player.getName();
        // 1. 先撤销上次残留授权（上次打开被拦截未真正发生，无关闭事件撤销），再重新判定授权：
        //    若先判定后撤销，旧授权被撤销后本次判定结果将失效（玩家失去访问权限，首次打不开）
        Map<UUID, Boolean> grants = temporaryGrants.get(location);
        Boolean previous = grants == null ? null : grants.remove(uuid);
        if (previous != null) {
            if (previous && !revokeGrant(block, uuid, true)) {
                // 撤销残留授权失败：恢复记录，后续再授权/禁用清理时重试
                temporaryGrants.computeIfAbsent(location, k -> new HashMap<>()).put(uuid, previous);
            }
            if (grants.isEmpty()) {
                temporaryGrants.remove(location);
            }
        }
        // 2. 检查玩家是否已有 PLAYER 级访问权限
        boolean granted = false;
        if (protection.getAccess(playerName, com.griefcraft.model.Permission.Type.PLAYER)
                != com.griefcraft.model.Permission.Access.PLAYER) {
            // 先落库（崩溃保险）：LWC 的 volatile 权限在插件禁用/服务器崩溃重启时会残留，
            // 启动清理时按记录移除这些临时权限；落库失败则中止授权
            if (!database.recordTempGrant(pluginType(), location, uuid, "")) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_GRANT_RECORD_FAIL, pluginType(), location));
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
                    // 落盘失败：内存权限已生效，必须定向回滚并回读确认；
                    // 回滚成功才删记录（无权限无记录），否则保留记录并标记已授权，交给撤销/启动清理重试
                    plugin.getLogger().fine("LWC temp grant failed: " + e.getMessage());
                    if (rollbackGrant(protection, playerName)) {
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
        // 3. 记录授权状态（无论是否已授权，均用于关闭时清理）
        temporaryGrants.computeIfAbsent(location, k -> new HashMap<>()).put(uuid, granted);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("临时授予 LWC 打开权限: " + player.getName() + " @ " + location
                    + (granted ? "" : "（已有权限，仅记录状态）"));
        }
    }

    @Override
    public void revokeAccess(Block block, Player player) {
        if (lwc == null || block == null || temporaryGrants.isEmpty()) {
            return;
        }
        BlockLocation location = BlockLocation.from(block);
        Map<UUID, Boolean> grants = temporaryGrants.get(location);
        if (grants == null) {
            return;
        }
        Boolean granted = grants.remove(player.getUniqueId());
        if (grants.isEmpty()) {
            temporaryGrants.remove(location);
        }
        if (granted != null && granted && !revokeGrantByLocation(location, player.getUniqueId())) {
            // 撤销失败（保护已消失/区块未加载）：恢复记录供下次授权/插件禁用清理时重试
            temporaryGrants.computeIfAbsent(location, k -> new HashMap<>()).put(player.getUniqueId(), granted);
        }
    }

    /** 按位置与 UUID 撤销临时授权；无法访问时返回 false。 */
    private boolean revokeGrantByLocation(BlockLocation location, UUID uuid) {
        if (lwc == null) {
            return false;
        }
        try {
            World world = location.toWorld();
            if (world == null) {
                return false;
            }
            return revokeGrant(world.getBlockAt(location.getX(), location.getY(), location.getZ()), uuid, true);
        } catch (Exception e) {
            plugin.getLogger().fine("LWC temp revoke failed: " + e.getMessage());
            return false;
        }
    }

    /** 定向回滚刚写入的 PLAYER 级临时权限并回读确认：真正撤销成功才返回 true。
     *  只移除该玩家的权限，不使用 removeTemporaryPermissions（会连带清掉他人/其它插件的 volatile 权限）。 */
    private boolean rollbackGrant(com.griefcraft.model.Protection protection, String playerName) {
        try {
            protection.removePermissions(playerName, com.griefcraft.model.Permission.Type.PLAYER);
            protection.save();
            return protection.getAccess(playerName, com.griefcraft.model.Permission.Type.PLAYER)
                    != com.griefcraft.model.Permission.Access.PLAYER;
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_REVOKE_FAIL, e.getMessage()));
            return false;
        }
    }

    /** 撤销单次临时授权记录：撤销成功才删除数据库记录；
     *  保护已消失/撤销失败返回 false，记录保留供启动清理兜底。 */
    private boolean revokeGrant(Block block, UUID uuid, boolean granted) {
        if (!granted || lwc == null) {
            return true;
        }
        try {
            com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
            com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
            if (protection == null) {
                return false;
            }
            // 撤销所有临时（volatile）权限
            protection.removeTemporaryPermissions();
            protection.save();
            // 权限已撤销，删除数据库记录（崩溃清理不再处理该条）
            try {
                database.deleteTempGrant(pluginType(), BlockLocation.from(block), uuid);
            } catch (Exception e) {
                plugin.getLogger().fine("清理临时授权记录失败: " + e.getMessage());
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("LWC temp revoke failed: " + e.getMessage());
            return false;
        }
    }

    // ==================== 生命周期 ====================

    /** 订阅 LWC 保护注册后回调（仅 LWC 插件存在时）。 */
    @Override
    public void register(Consumer<Block> protectionCreatedHandler) {
        this.protectionCreatedHandler = protectionCreatedHandler;
        Object lwcPlugin = Bukkit.getPluginManager().getPlugin("LWC");
        if (lwcPlugin != null) {
            lwc = ((com.griefcraft.lwc.LWCPlugin) lwcPlugin).getLWC();
            Object module = createLwcModule();
            ((com.griefcraft.lwc.LWC) lwc).getModuleLoader().registerModule(plugin, (com.griefcraft.scripting.Module) module);
        }
    }

    /** 注销监听（插件禁用时调用）：先撤销在线玩家的临时授权，再按插件移除模块。 */
    @Override
    public void unregister() {
        cleanup();
        if (lwc != null) {
            try {
                ((com.griefcraft.lwc.LWC) lwc).getModuleLoader().removeModules(plugin);
            } catch (Exception e) {
                // LWC 已随禁用流程卸载时忽略
            }
            lwc = null;
        }
    }

    /** 撤销全部在线玩家当前持有的临时 LWC 访问授权（禁用前调用，避免授权泄漏到下次启用）。 */
    @Override
    public void cleanup() {
        revokeAllTemporary();
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

    /**
     * 插件启动时依据数据库记录清理崩溃残留的 LWC 临时授权（volatile 权限在插件禁用或服务器重启时不会自动移除）：
     * 逐条按记录移除保护上的所有临时权限，成功后删除记录；恢复失败的记录保留，下次启动重试。
     * 需在 {@link #register} 之后调用（依赖已初始化的 LWC 实例）。
     */
    @Override
    public void cleanupStale() {
        if (lwc == null) {
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
                if (revokeFromRecord(block)) {
                    database.deleteTempGrant(pluginType(), loc, record.getPlayerUuid());
                    plugin.getLogger().info(Messages.getLog(Messages.LOG_STALE_CLEANED, pluginType(), loc));
                } else {
                    // 恢复未确认成功（保护已消失）：保留记录下次启动重试，避免授权残留无法追踪
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_TEMP_STALE_KEEP, pluginType(), loc));
                }
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_STALE_CLEAN_FAIL, e.getMessage()));
            }
        }
    }

    /** 依据记录撤销崩溃残留的 LWC 临时授权：移除保护上的全部临时（volatile）权限；恢复成功返回 true。 */
    private boolean revokeFromRecord(Block block) {
        com.griefcraft.lwc.LWC lwcInstance = (com.griefcraft.lwc.LWC) lwc;
        com.griefcraft.model.Protection protection = lwcInstance.findProtection(block);
        if (protection == null) {
            return false;
        }
        protection.removeTemporaryPermissions();
        protection.save();
        return true;
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
                    // LWC 事件回调线程不保证为主线程：自动卸锁涉及 DB 写与掉落物，调度回主线程执行
                    if (protection != null && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> protectionCreatedHandler.accept(protection.getBlock()));
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
