package com.lonleaf.chestTheft;

import com.lonleaf.chestTheft.Game.GameManager;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ChestTheft extends JavaPlugin {

    public static ChestTheft instance;

    public static NamespacedKey key;

    private final File dataFolder = getDataFolder();
    public static FileConfiguration dataLanguageConfig;

    private GameManager gameManager;

    @Override
    public void onEnable() {
        instance = this;
        // 确保插件数据文件夹存在
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.saveDefaultConfig();
        // 读取配置值
        loadConfig();
        key = new NamespacedKey(this, "ChestTheft");
        this.gameManager = new GameManager(this);
        getServer().getPluginManager().registerEvents(new ChestListener(gameManager), this);

    }

    public void loadConfig() {
        // 加载默认的 config.yml 文件
        reloadConfig();
        this.saveDefaultConfig();
        File dataLanguageFile = new File(dataFolder, "lang.yml");
        if (!dataLanguageFile.exists()) {
            saveResource("lang.yml", false);  // 复制默认的 lang.yml 文件到插件目录
        }
        if(!(new File(dataFolder, "database.db")).exists()){
            saveResource("database.db", false);
        }
        dataLanguageConfig = YamlConfiguration.loadConfiguration(dataLanguageFile);
    }

    public String language(String path){
        return dataLanguageConfig.getString(path);
    }

    public ChestTheft getInstance(){return instance;}

    @Override
    public void onDisable() {
        gameManager.cleanup();
    }
}
