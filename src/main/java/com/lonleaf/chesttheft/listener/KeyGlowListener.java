package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.KeyGlowTask;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * 钥匙发光与箱子开合联动：真实容器方块打开时移除发光展示实体
 * （避免原版开盖动画与静态展示实体错位），关闭后由 KeyGlowTask 下个对账周期恢复。
 */
public class KeyGlowListener implements Listener {

    private final KeyGlowTask keyGlowTask;

    public KeyGlowListener(KeyGlowTask keyGlowTask) {
        this.keyGlowTask = keyGlowTask;
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Container container) {
            keyGlowTask.onChestOpen(BlockLocation.from(container.getBlock()));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Container container) {
            keyGlowTask.onChestClose(BlockLocation.from(container.getBlock()));
        }
    }
}
