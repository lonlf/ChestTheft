package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemDefinition;
import com.lonleaf.chesttheft.item.ItemManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.Collectors;

public class GiveCommand implements Command {
    private final ItemManager itemManager;
    private final ItemConfigManager configManager;

    public GiveCommand(ItemManager itemManager, ItemConfigManager configManager) {
        this.itemManager = itemManager;
        this.configManager = configManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            return false;
        }
        if (!sender.hasPermission("chesttheft.admin")) {
            Messages.send(sender, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return true;
        }
        if (args.length < 3) {
            Messages.send(sender, Messages.USAGE, Messages.USAGE_FORMAT);
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            Messages.send(sender, Messages.PLAYER_NOT_FOUND, Messages.PLAYER_NOT_FOUND_FORMAT);
            return true;
        }
        ItemDefinition def = configManager.getDefinition(args[2]);
        if (def == null) {
            Messages.send(sender, Messages.INVALID_ID, Messages.INVALID_ID_FORMAT, args[2]);
            return true;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException e) {
                Messages.send(sender, Messages.INVALID_AMOUNT, Messages.INVALID_AMOUNT_FORMAT);
                return true;
            }
        }
        ItemStack item = itemManager.create(def.getId(), amount);
        if (item == null) {
            return true;
        }
        target.getInventory().addItem(item);
        Messages.send(sender, Messages.GIVEN_ITEM, Messages.GIVEN_ITEM_FORMAT, amount, def.getId(), target.getName());
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return null; // 返回 null 交给 Bukkit 默认补全在线玩家
        }
        if (args.length == 3) {
            List<String> completions = configManager.getDefinitions().stream()
                    .map(ItemDefinition::getId)
                    .collect(Collectors.toList());
            return filter(completions, args[2]);
        }
        return List.of();
    }
}
