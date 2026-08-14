package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

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
            Messages.send(sender, Messages.PLAYER_ONLY, Messages.PLAYER_ONLY_FORMAT);
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            Messages.send(player, Messages.SETITEM_NO_ITEM, Messages.SETITEM_NO_ITEM_FORMAT);
            return true;
        }
        ItemType type = itemManager.getType(hand);
        if (type == null) {
            Messages.send(player, Messages.CHECK_NOT_SPECIAL, Messages.CHECK_NOT_SPECIAL_FORMAT);
        } else {
            Messages.send(player, Messages.CHECK_PDC, Messages.CHECK_PDC_FORMAT,
                    itemManager.getId(hand),
                    type.name().toLowerCase(Locale.ROOT));
            // 只有钥匙需要显示配对锁与锁凭证
            if (type == ItemType.KEY) {
                BlockLocation paired = itemManager.getPairedLock(hand);
                String token = itemManager.getPairedToken(hand);
                Messages.send(player, Messages.CHECK_KEY_INFO, Messages.CHECK_KEY_INFO_FORMAT,
                        paired == null ? Messages.NONE : paired.toString(),
                        token == null ? Messages.NONE : token);
            }
        }
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
