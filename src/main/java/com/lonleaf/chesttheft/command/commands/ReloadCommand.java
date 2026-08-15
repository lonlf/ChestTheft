package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.minigame.GameManager;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand implements Command {
    private final PluginConfig config;
    private final ItemConfigManager itemConfigManager;
    private final GameManager gameManager;
    private final LockConfigManager lockConfigManager;

    public ReloadCommand(PluginConfig config, ItemConfigManager itemConfigManager,
                         GameManager gameManager, LockConfigManager lockConfigManager) {
        this.config = config;
        this.itemConfigManager = itemConfigManager;
        this.gameManager = gameManager;
        this.lockConfigManager = lockConfigManager;
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
        gameManager.updateConfig(config.getGameConfig());
        lockConfigManager.load();
        lockConfigManager.updateDefaultConfig(config.getGameConfig());
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
