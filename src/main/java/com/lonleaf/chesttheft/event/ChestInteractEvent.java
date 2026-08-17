package com.lonleaf.chesttheft.event;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 已上锁箱子被交互时触发（撬锁/钥匙/直接点击等一切交互的入口）。
 * 外部保护集成在此决定：保护所有者接管执行卸锁，或非所有者在关闭撬锁时阻止全部操作；取消后本插件不再处理该次交互。
 */
public class ChestInteractEvent extends ChestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public ChestInteractEvent(Player player, Block block, BlockLocation location) {
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
