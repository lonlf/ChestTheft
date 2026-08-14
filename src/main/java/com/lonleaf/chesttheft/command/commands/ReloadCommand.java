package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * reload 子命令：重载插件配置、物品配置与语言文件。
 */
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
            sender.sendMessage(Messages.get(Messages.NO_PERMISSION));
            return true;
        }

        config.reload();
        itemConfigManager.reload();
        Messages.reload(config.getLanguage());
        sender.sendMessage(Messages.get(Messages.RELOADED));
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
