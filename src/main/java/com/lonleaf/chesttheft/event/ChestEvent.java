package com.lonleaf.chesttheft.event;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * 本插件核心操作事件基类：核心操作执行前触发对应子类事件，
 * 外部插件（如 LWC / Bolt 集成）可监听并取消事件来阻止操作，实现核心逻辑与保护插件集成的解耦。
 */
public abstract class ChestEvent extends Event implements Cancellable {

    private final Player player;
    private final Block block;
    private final BlockLocation location;
    private boolean cancelled;

    protected ChestEvent(Player player, Block block, BlockLocation location) {
        this.player = player;
        this.block = block;
        this.location = location;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getBlock() {
        return block;
    }

    public BlockLocation getLocation() {
        return location;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
