package com.lonleaf.chesttheft.event;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 使用匹配钥匙打开箱子前触发（含手持钥匙与背包钥匙）；取消后阻止打开。
 * 本插件 LWC / Bolt 集成的交互入口事件已过滤非所有者，此事件主要供其他插件扩展使用。
 */
public class ChestKeyOpenEvent extends ChestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public ChestKeyOpenEvent(Player player, Block block, BlockLocation location) {
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
