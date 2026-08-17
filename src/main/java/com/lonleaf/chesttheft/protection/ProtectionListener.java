package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.event.ChestInteractEvent;
import com.lonleaf.chesttheft.event.ChestLockEvent;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;

/**
 * 外部保护（LWC / Bolt）集成总调度：监听核心操作事件决定放行或阻止（受保护箱子在关闭撬锁时阻止
 * 非所有者的一切交互与上锁，保护所有者接管卸锁；启用撬锁时仅保护所有者可上锁），并装配
 * {@link BoltProtectionListener} / {@link LwcProtectionListener} 两个分插件集成类，提供共享自动卸锁处理。
 */
public class ProtectionListener implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ChestService chestService;
    private final ItemManager itemManager;
    /** Bolt 集成（软依赖，未安装时注册的监听器均为空操作）。 */
    private BoltProtectionListener boltProtection;
    /** LWC 集成（软依赖，未安装时不注册模块）。 */
    private LwcProtectionListener lwcProtection;

    public ProtectionListener(Plugin plugin, PluginConfig config, ChestService chestService, ItemManager itemManager) {
        this.plugin = plugin;
        this.config = config;
        this.chestService = chestService;
        this.itemManager = itemManager;
    }

    // ==================== 本插件核心操作事件（共享，LWC / Bolt 通用） ====================

    /**
     * 已上锁箱子交互入口：受保护且关闭撬锁时，保护所有者接管卸锁、非所有者取消全部交互；
     * 启用撬锁（P=true）时不做任何干预（锁保留，可被撬开）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestInteract(ChestInteractEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (block == null || !ProtectionUtil.isProtected(block)) {
            return;
        }
        // 启用撬锁（P=true）：保护所有者也不自动卸锁，锁保留，按正常已上锁逻辑处理（钥匙/撬锁）
        if (config.isProtectionPickingEnabled()) {
            return;
        }
        if (ProtectionUtil.isOwner(block, player)) {
            // 关闭撬锁（P=false）：保护所有者接管卸锁（锁返还上锁者，仅提示所有者一条消息）
            event.setCancelled(true);
            unlockAndReturn(block);
            Messages.send(player, Messages.CHEST_PROTECTED, Messages.CHEST_PROTECTED_FORMAT);
            return;
        }
        event.setCancelled(true);
        Messages.send(player, Messages.CHEST_PROTECTED_ACCESS_DENIED, Messages.CHEST_PROTECTED_ACCESS_DENIED_FORMAT);
    }

    /**
     * 上锁前：受保护箱子的上锁限制。关闭撬锁（P=false）时一律禁止上锁；启用撬锁（P=true）时
     * 仅保护所有者可以上锁，避免箱子被其他玩家占用。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestLock(ChestLockEvent event) {
        Block block = event.getBlock();
        if (block == null || !ProtectionUtil.isProtected(block)) {
            return;
        }
        if (config.isProtectionPickingEnabled()) {
            if (!ProtectionUtil.isOwner(block, event.getPlayer())) {
                event.setCancelled(true);
                Messages.send(event.getPlayer(), Messages.CHEST_PROTECTED_LOCK_DENIED, Messages.CHEST_PROTECTED_LOCK_DENIED_FORMAT);
            }
        } else {
            event.setCancelled(true);
            Messages.send(event.getPlayer(), Messages.CHEST_PROTECTED_LOCK_DENIED, Messages.CHEST_PROTECTED_LOCK_DENIED_FORMAT);
        }
    }

    // ==================== 装配与注销 ====================

    /** 注册监听：Bolt 作为 Bukkit 监听器注册并订阅保护创建事件；LWC 注册脚本模块（均仅对应插件存在时生效）。 */
    public void register() {
        boltProtection = new BoltProtectionListener(plugin, config, this::handleProtectionCreated);
        lwcProtection = new LwcProtectionListener(plugin, this::handleProtectionCreated);
        Bukkit.getPluginManager().registerEvents(boltProtection, plugin);
        boltProtection.register();
        lwcProtection.register();
    }

    /** 注销监听（插件禁用时调用）。 */
    public void unregister() {
        if (boltProtection != null) {
            boltProtection.unregister();
            boltProtection = null;
        }
        if (lwcProtection != null) {
            lwcProtection.unregister();
            lwcProtection = null;
        }
    }

    // ==================== 共享：保护创建后自动卸锁 ====================

    /** 核心处理：撬锁关闭且箱子已被本插件上锁时，自动卸下锁并返还、提示上锁者。 */
    private void handleProtectionCreated(Block block) {
        // 插件已禁用（Bolt 回调无取消订阅机制）或撬锁功能启用时不处理
        if (!plugin.isEnabled() || block == null || config.isProtectionPickingEnabled() || !chestService.isLocked(block)) {
            return;
        }
        unlockAndReturn(block);
        if (config.isDebug()) {
            plugin.getLogger().info(Messages.getLog(Messages.LOG_PROTECTION_AUTO_UNLOCK, BlockLocation.from(block)));
        }
    }

    /**
     * 卸下锁并返还锁物品：先取上锁者（卸锁会删除数据库记录），再卸锁；
     * 锁返还给在线的上锁者（直接入包，背包满则掉落），不在线时才掉落；
     * 自动补默认锁 / PDC 标记，避免"假锁"或物品消失。
     */
    private void unlockAndReturn(Block block) {
        String lockerUuid = chestService.getLocker(block);
        ItemStack lockItem = chestService.unlock(block);
        if (lockItem == null || lockItem.getType().isAir()) {
            // 数据库无锁物品数据（旧数据/反序列化失败）时补发默认锁，避免锁物品凭空消失
            lockItem = itemManager.createDefault(ItemType.LOCK, 1);
        } else if (itemManager.getId(lockItem) == null) {
            // 旧数据序列化丢失 PDC：重新标记为锁物品，避免返还后成为无法上锁/使用的"假锁"
            itemManager.tagAsDefault(lockItem, ItemType.LOCK);
        }
        if (lockItem != null) {
            giveLockItem(block, lockerUuid, lockItem);
        }
        notifyLocker(lockerUuid);
    }

    /**
     * 返还锁物品：上锁者在线时直接放入其背包（背包满则掉落），不在线时才掉落。
     * 直接入包可避免掉落物因拾取范围/位置问题导致"锁消失"，保证返还可靠。
     */
    private void giveLockItem(Block block, String lockerUuid, ItemStack lockItem) {
        Player owner = resolveOnlineLocker(lockerUuid);
        if (owner != null) {
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(lockItem);
            for (ItemStack drop : leftover.values()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), drop);
            }
        } else {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), lockItem);
        }
    }

    /** 解析在线的上锁者；不在线或 UUID 非法时返回 null。 */
    private Player resolveOnlineLocker(String lockerUuid) {
        if (lockerUuid == null || lockerUuid.isEmpty()) {
            return null;
        }
        try {
            Player owner = Bukkit.getPlayer(UUID.fromString(lockerUuid));
            return (owner != null && owner.isOnline()) ? owner : null;
        } catch (IllegalArgumentException e) {
            // 非法 UUID（旧数据）
            return null;
        }
    }

    /** 提示在线的上锁者：锁因箱子受外部保护被自动卸下。 */
    private void notifyLocker(String lockerUuid) {
        Player owner = resolveOnlineLocker(lockerUuid);
        if (owner != null) {
            Messages.send(owner, Messages.CHEST_PROTECTED, Messages.CHEST_PROTECTED_FORMAT);
        }
    }
}
