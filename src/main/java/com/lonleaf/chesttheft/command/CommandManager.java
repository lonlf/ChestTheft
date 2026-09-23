package com.lonleaf.chesttheft.command;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.command.commands.CheckCommand;
import com.lonleaf.chesttheft.command.commands.Command;
import com.lonleaf.chesttheft.command.commands.DebugCommand;
import com.lonleaf.chesttheft.command.commands.GiveCommand;
import com.lonleaf.chesttheft.command.commands.LootChestCommand;
import com.lonleaf.chesttheft.command.commands.ReloadCommand;
import com.lonleaf.chesttheft.command.commands.SetItemCommand;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemTagger;
import com.lonleaf.chesttheft.lootchest.LootChestManager;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.recipe.RecipeManager;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import org.bukkit.command.PluginCommand;

import java.util.ArrayList;
import java.util.List;

public class CommandManager {
    private final ChestTheft plugin;
    private final Command giveCommand;
    private final Command setItemCommand;
    private final Command checkCommand;
    private final Command reloadCommand;
    private final Command lootChestCommand;
    private final Command debugCommand;

    public CommandManager(ChestTheft plugin, ItemManager itemManager, ItemTagger itemTagger,
                          ItemConfigManager itemConfigManager, PluginConfig config, GameManager gameManager,
                          LockConfigManager lockConfigManager, TriggerManager triggerManager,
                          LootChestManager lootChestManager, RecipeManager recipeManager) {
        this.plugin = plugin;
        this.giveCommand = new GiveCommand(itemManager, itemConfigManager);
        this.setItemCommand = new SetItemCommand(itemTagger, itemConfigManager);
        this.checkCommand = new CheckCommand(itemManager);
        this.reloadCommand = new ReloadCommand(config, itemConfigManager, gameManager, lockConfigManager, triggerManager, lootChestManager, recipeManager);
        this.lootChestCommand = new LootChestCommand(lootChestManager);
        this.debugCommand = new DebugCommand(gameManager, lockConfigManager);
        registerCommands();
    }

    public void registerCommands() {
        PluginCommand chestTheftCommand = plugin.getCommand("chesttheft");
        if (chestTheftCommand != null) {
            chestTheftCommand.setExecutor((sender, command, label, args) -> {
                if (args.length == 0) {
                    Messages.send(sender, Messages.USAGE, Messages.USAGE_FORMAT);
                    return true;
                }
                List<Boolean> result = new ArrayList<>();
                result.add(giveCommand.execute(sender, args));
                result.add(setItemCommand.execute(sender, args));
                result.add(checkCommand.execute(sender, args));
                result.add(reloadCommand.execute(sender, args));
                result.add(lootChestCommand.execute(sender, args));
                result.add(debugCommand.execute(sender, args));
                return result.contains(Boolean.TRUE);
            });
            chestTheftCommand.setTabCompleter((sender, command, label, args) -> {
                if (args.length == 0) {
                    return List.of("give", "setitem", "check", "reload", "lootchest", "debug");
                }
                return switch (args[0]) {
                    case "give" -> giveCommand.completeList(args);
                    case "setitem" -> setItemCommand.completeList(args);
                    case "check" -> checkCommand.completeList(args);
                    case "reload" -> reloadCommand.completeList(args);
                    case "lootchest" -> lootChestCommand.completeList(args);
                    case "debug" -> debugCommand.completeList(args);
                    default -> List.of("give", "setitem", "check", "reload", "lootchest", "debug");
                };
            });
        }
    }
}
