package com.lonleaf.chesttheft.event;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 对未上锁箱子执行上锁前触发。
 * 外部保护集成监听此事件：受保护且关闭撬锁时取消，阻止对受保护箱子上锁；取消后本插件不消耗锁物品、不写入数据库。
 */
public class ChestLockEvent extends ChestEvent {

    private static final HandlerList HANDLERS = new HandlerList();
    private final ItemStack lockItem;

    public ChestLockEvent(Player player, Block block, BlockLocation location, ItemStack lockItem) {
        super(player, block, location);
        this.lockItem = lockItem;
    }

    /** 手中用于上锁的锁物品（未消耗前的引用）。 */
    public ItemStack getLockItem() {
        return lockItem;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
