package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemDefinition;
import com.lonleaf.chesttheft.item.ItemTagger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.Collectors;

public class SetItemCommand implements Command {
    private final ItemTagger tagger;
    private final ItemConfigManager configManager;

    public SetItemCommand(ItemTagger tagger, ItemConfigManager configManager) {
        this.tagger = tagger;
        this.configManager = configManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("setitem")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, Messages.PLAYER_ONLY, Messages.PLAYER_ONLY_FORMAT);
            return true;
        }
        if (!player.hasPermission("chesttheft.admin")) {
            Messages.send(player, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return true;
        }
        if (args.length < 2) {
            Messages.send(player, Messages.USAGE, Messages.USAGE_FORMAT);
            return true;
        }
        ItemDefinition def = configManager.getDefinition(args[1]);
        if (def == null) {
            Messages.send(player, Messages.INVALID_ID, Messages.INVALID_ID_FORMAT, args[1]);
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            Messages.send(player, Messages.SETITEM_NO_ITEM, Messages.SETITEM_NO_ITEM_FORMAT);
            return true;
        }
        tagger.tag(hand, def.getId());
        Messages.send(player, Messages.SETITEM_SUCCESS, Messages.SETITEM_SUCCESS_FORMAT, def.getId());
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            List<String> completions = configManager.getDefinitions().stream()
                    .map(ItemDefinition::getId)
                    .collect(Collectors.toList());
            return filter(completions, args[1]);
        }
        return List.of();
    }
}
