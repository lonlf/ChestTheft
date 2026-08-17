package com.lonleaf.chesttheft.event;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 打开上锁箱子容器前触发（钥匙打开 / 撬锁成功均触发）。
 * 外部保护集成在此为玩家临时授予访问权限（如 Bolt access），使打开容器的瞬间不被保护插件拦截；取消后不打开箱子。
 */
public class ChestOpenEvent extends ChestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public ChestOpenEvent(Player player, Block block, BlockLocation location) {
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
