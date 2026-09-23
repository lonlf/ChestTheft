package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.lootchest.LootChestManager;
import com.lonleaf.chesttheft.service.ChestService;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * 漏斗守卫：拦截以"已上锁容器 / 战利品箱"为来源或目标的原版物品搬运（漏斗、漏斗矿车等）。
 * 锁原本只拦玩家右键，漏斗可直接抽干箱子；判定全程走内存锁索引，无数据库访问。
 */
public class HopperGuardListener implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ChestService chestService;
    private final LootChestManager lootChestManager;

    public HopperGuardListener(Plugin plugin, PluginConfig config, ChestService chestService,
                               LootChestManager lootChestManager) {
        this.plugin = plugin;
        this.config = config;
        this.chestService = chestService;
        this.lootChestManager = lootChestManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (config.isHopperGuardExtract() && isGuarded(event.getSource())) {
            event.setCancelled(true);
            debug("extract", event);
            return;
        }
        if (config.isHopperGuardInsert() && isGuarded(event.getDestination())) {
            event.setCancelled(true);
            debug("insert", event);
        }
    }

    /** 该库存所属方块是否为受保护容器；双箱任一上锁即视为受保护。 */
    private boolean isGuarded(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            return isGuardedSide(doubleChest.getLeftSide()) || isGuardedSide(doubleChest.getRightSide());
        }
        return isGuardedSide(holder);
    }

    private boolean isGuardedSide(InventoryHolder holder) {
        // 矿车 / GUI / 虚拟库存没有对应方块，直接忽略
        if (!(holder instanceof Container container)) {
            return false;
        }
        Block block = container.getBlock();
        if (!config.isLockable(block.getType())) {
            return false;
        }
        return chestService.isLocked(block) || lootChestManager.findByBlock(block) != null;
    }

    private void debug(String direction, InventoryMoveItemEvent event) {
        if (config.isDebug()) {
            plugin.getLogger().info("[ChestTheft][DEBUG] hopper-guard blocked " + direction
                    + " | initiator=" + describe(event.getInitiator())
                    + ", source=" + describe(event.getSource())
                    + ", destination=" + describe(event.getDestination()));
        }
    }

    private String describe(Inventory inventory) {
        if (inventory == null) {
            return "null";
        }
        InventoryHolder holder = inventory.getHolder();
        return holder == null ? inventory.getType().name() : holder.getClass().getSimpleName();
    }
}
