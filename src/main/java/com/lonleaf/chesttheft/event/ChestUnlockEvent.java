package com.lonleaf.chesttheft.event;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 卸下锁并返还锁物品前触发；取消后不执行卸锁。
 * 本插件 LWC / Bolt 集成在交互入口事件中已处理保护所有者卸锁与非所有者阻止，此事件主要供其他插件扩展使用。
 */
public class ChestUnlockEvent extends ChestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public ChestUnlockEvent(Player player, Block block, BlockLocation location) {
        super(player, block, location);
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
