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

/**
 * setitem 子命令：将玩家手中的物品写入持久化标签，设置为指定 ID 的特殊物品。
 */
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
            sender.sendMessage(Messages.get(Messages.PLAYER_ONLY));
            return true;
        }
        if (!player.hasPermission("chesttheft.admin")) {
            player.sendMessage(Messages.get(Messages.NO_PERMISSION));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Messages.get(Messages.USAGE));
            return true;
        }
        ItemDefinition def = configManager.getDefinition(args[1]);
        if (def == null) {
            player.sendMessage(Messages.get(Messages.INVALID_ID, args[1]));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(Messages.get(Messages.SETITEM_NO_ITEM));
            return true;
        }
        tagger.tag(hand, def.getId());
        player.sendMessage(Messages.get(Messages.SETITEM_SUCCESS, def.getId()));
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
