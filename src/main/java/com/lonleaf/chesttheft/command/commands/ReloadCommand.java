package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.lootchest.LootChestManager;
import com.lonleaf.chesttheft.message.BitmapCalculator;
import com.lonleaf.chesttheft.message.OffsetChars;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand implements Command {
    private final PluginConfig config;
    private final ItemConfigManager itemConfigManager;
    private final GameManager gameManager;
    private final LockConfigManager lockConfigManager;
    private final TriggerManager triggerManager;
    private final LootChestManager lootChestManager;

    public ReloadCommand(PluginConfig config, ItemConfigManager itemConfigManager,
                         GameManager gameManager, LockConfigManager lockConfigManager,
                         TriggerManager triggerManager, LootChestManager lootChestManager) {
        this.config = config;
        this.itemConfigManager = itemConfigManager;
        this.gameManager = gameManager;
        this.lockConfigManager = lockConfigManager;
        this.triggerManager = triggerManager;
        this.lootChestManager = lootChestManager;
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
        // 位图渲染工具持有 FontConfig 静态引用，仅在启动时注入；reload 后必须重新注入新配置，
        // 否则 font.bitmap（tumbler-ascent 等）与 tumbler-chars 的修改不生效
        OffsetChars.init(config.getFontConfig());
        BitmapCalculator.init(config.getFontConfig());
        itemConfigManager.reload();
        gameManager.updateConfig(config.getGameConfig());
        // 必须先更新默认配置再加载等级配置：load() 内 mergeWithDefault 使用 defaultConfig 填充未定义键
        lockConfigManager.updateDefaultConfig(config.getGameConfig());
        lockConfigManager.load();
        triggerManager.load();
        // load() 清空了触发器注册表，须重新注册物品定义中的内嵌触发器（临时 id）
        triggerManager.syncItemTriggers(itemConfigManager.getDefinitions());
        lootChestManager.reloadConfig();
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
