package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * check 子命令：检查玩家手中物品的特殊类型（key / lock / picker）。
 */
public class CheckCommand implements Command {
    private final ItemManager itemManager;

    public CheckCommand(ItemManager itemManager) {
        this.itemManager = itemManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("check")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get(Messages.PLAYER_ONLY));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(Messages.get(Messages.SETITEM_NO_ITEM));
            return true;
        }
        ItemType type = itemManager.getType(hand);
        if (type == null) {
            player.sendMessage(Messages.get(Messages.CHECK_NOT_SPECIAL));
        } else {
            player.sendMessage(Messages.get(Messages.CHECK_TYPE, type.name().toLowerCase(Locale.ROOT)));
        }
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
