package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.lootchest.LootChestManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class LootChestCommand implements Command {
    private final LootChestManager lootChestManager;

    public LootChestCommand(LootChestManager lootChestManager) {
        this.lootChestManager = lootChestManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("lootchest")) {
            return false;
        }
        if (!sender.hasPermission("chesttheft.admin")) {
            Messages.send(sender, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return true;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, Messages.PLAYER_ONLY, Messages.PLAYER_ONLY_FORMAT);
            return true;
        }
        if (args.length < 2) {
            Messages.send(sender, Messages.USAGE, Messages.USAGE_FORMAT);
            return true;
        }
        String profileId = args[1];
        if (lootChestManager.spawnChest(player.getLocation().getBlock().getLocation(), profileId) == null) {
            Messages.send(sender, Messages.LOOT_CHEST_NOT_FOUND, Messages.LOOT_CHEST_NOT_FOUND_FORMAT, profileId);
            return true;
        }
        Messages.send(sender, Messages.LOOT_CHEST_SPAWNED, Messages.LOOT_CHEST_SPAWNED_FORMAT, profileId);
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return filter(lootChestManager.getProfileIds(), args[1]);
        }
        return List.of();
    }
}
