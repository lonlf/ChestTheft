package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.KeyGlowTask;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** 钥匙发光与箱子开合联动：容器打开时移除发光实体（避免开盖动画错位），关闭后由 KeyGlowTask 恢复。 */
public class KeyGlowListener implements Listener {

    private final KeyGlowTask keyGlowTask;

    public KeyGlowListener(KeyGlowTask keyGlowTask) {
        this.keyGlowTask = keyGlowTask;
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Block block = containerBlockOf(event.getInventory());
        if (block != null) {
            keyGlowTask.onChestOpen(BlockLocation.from(block));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Block block = containerBlockOf(event.getInventory());
        if (block != null) {
            keyGlowTask.onChestClose(BlockLocation.from(block));
        }
    }

    /** 玩家开关门时：立即销毁该门的发光展示实体，5 tick 后按最新状态重生（由 KeyGlowTask.onDoorInteract 执行），
     *  替代原先 KeyGlowTask 每 5 tick 轮询门状态的逻辑，事件驱动响应更快。 */
    @EventHandler
    public void onDoorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.isCancelled()) {
            return;
        }
        Block block = event.getClickedBlock();
        // 门（木门/铁门等上下半一体的 *_DOOR；陷阱门 *_TRAPDOOR 不在此列）
        if (block == null || !block.getType().name().endsWith("_DOOR")
                || block.getType().name().endsWith("_TRAPDOOR")) {
            return;
        }
        keyGlowTask.onDoorInteract(BlockLocation.from(block));
    }

    /** 从容器库存反查一个箱子方块：单箱返回自身，双箱返回任意一侧（KeyGlowTask 会映射到统一发光 key）。 */
    private Block containerBlockOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) {
            return container.getBlock();
        }
        if (holder instanceof DoubleChest doubleChest) {
            // 双箱的 holder 为 DoubleChest（不实现 Container），取任意一侧的方块即可
            InventoryHolder side = doubleChest.getLeftSide();
            if (side instanceof Chest chest) {
                return chest.getBlock();
            }
        }
        return null;
    }
}
