package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand implements Command {
    private final PluginConfig config;
    private final ItemConfigManager itemConfigManager;

    public ReloadCommand(PluginConfig config, ItemConfigManager itemConfigManager) {
        this.config = config;
        this.itemConfigManager = itemConfigManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            return false;
        }
        if (!sender.hasPermission("chesttheft.admin")) {
            Messages.send(sender, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return true;
        }

        config.reload();
        itemConfigManager.reload();
        Messages.reload(config.getLanguage());
        Messages.applyFormats(config.getMessageFormats());
        Messages.send(sender, Messages.RELOADED, Messages.RELOADED_FORMAT);
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
