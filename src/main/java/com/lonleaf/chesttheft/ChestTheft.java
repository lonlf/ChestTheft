package com.lonleaf.chesttheft;

import com.lonleaf.chesttheft.command.CommandManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.database.DatabaseManager;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemTagger;
import com.lonleaf.chesttheft.listener.ChestListener;
import com.lonleaf.chesttheft.service.ChestService;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChestTheft extends JavaPlugin {

    private DatabaseManager databaseManager;
    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginConfig config = new PluginConfig(this);
        // 初始化消息系统：提取内置语言文件并加载 config 中指定的语言（首启时语言已自动检测写入 config）
        Messages.init(getDataFolder().toPath(), config.getLanguage());
        if (config.isLanguageDetected()) {
            getLogger().info(Messages.getLog(Messages.LOG_LANG_DETECTED, config.getDetectedLocale(), config.getLanguage()));
        }

        databaseManager = new DatabaseManager(this, config);
        ChestService chestService = new ChestService(databaseManager.getDatabase());
        ItemTagger itemTagger = new ItemTagger(new NamespacedKey(this, "item_id"), new NamespacedKey(this, "paired_lock"));
        ItemConfigManager itemConfigManager = new ItemConfigManager(this);
        ItemManager itemManager = new ItemManager(itemTagger, itemConfigManager);
        gameManager = new GameManager(this, config);

        getServer().getPluginManager().registerEvents(new ChestListener(chestService, gameManager, itemManager, config), this);

        new CommandManager(this, itemManager, itemTagger, itemConfigManager, config);

        getLogger().info(Messages.getLog(Messages.LOG_ENABLED));
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.cleanup();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}
