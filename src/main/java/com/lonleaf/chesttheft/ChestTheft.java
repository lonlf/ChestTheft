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
import com.lonleaf.chesttheft.packet.PacketManager;
import com.lonleaf.chesttheft.service.ChestService;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChestTheft extends JavaPlugin {

    private DatabaseManager databaseManager;
    private GameManager gameManager;

    @Override
    public void onLoad() {
        // 初始化 PacketEvents（需在 onEnable 前完成，之后通过 PacketEvents.getAPI() 使用）
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        // 加载：解析服务器版本、初始化反射工具（build() 仅创建实例，必须显式调用 load()）
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        // 注意：此处不调用 saveDefaultConfig()，首次启动的配置生成与语言检测写回统一交由 PluginConfig.load()
        // 处理（它通过 config.yml 是否存在判断 firstStart）。若在 onEnable 提前生成配置文件，
        // PluginConfig.load() 会误判为非首次启动，导致 applySystemLanguage() 永不执行。

        // 预读语言：未设置时用系统检测值，先行初始化消息系统（供后续配置解析与日志使用）
        String lang = getConfig().getString("language", "");
        if (lang == null || lang.isBlank()) {
            lang = Messages.detectSystemLanguage();
        }
        Messages.init(getDataFolder().toPath(), lang);

        // PacketEvents 初始化：注册内部 Bukkit 监听器并完成通道注入（须在注册包监听器之前）
        PacketEvents.getAPI().getSettings().reEncodeByDefault(true);
        PacketEvents.getAPI().init();
        // 配置管理器：加载全部配置（含首启语言检测写回、config-version 自动升级）
        PluginConfig config = new PluginConfig(this);
        // 应用 config.yml 中 message-format 小节的消息显示方式配置
        Messages.applyFormats(config.getMessageFormats());
        if (config.isLanguageDetected()) {
            getLogger().info(Messages.getLog(Messages.LOG_LANG_DETECTED, config.getDetectedLocale(), config.getLanguage()));
        }

        databaseManager = new DatabaseManager(this, config);
        ChestService chestService = new ChestService(databaseManager.getDatabase());
        ItemTagger itemTagger = new ItemTagger(new NamespacedKey(this, "item_id"),
                new NamespacedKey(this, "paired_lock"), new NamespacedKey(this, "paired_token"));
        ItemConfigManager itemConfigManager = new ItemConfigManager(this);
        ItemManager itemManager = new ItemManager(itemTagger, itemConfigManager);
        // 协议包模块：为特殊物品动态注入 Lore 展示信息（类型、配对状态等）
        new PacketManager(this, config, itemConfigManager);
        gameManager = new GameManager(this, config);

        getServer().getPluginManager().registerEvents(new ChestListener(chestService, gameManager, itemManager, config), this);

        new CommandManager(this, itemManager, itemTagger, itemConfigManager, config);

        getLogger().info(Messages.getLog(Messages.LOG_ENABLED));
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        if (gameManager != null) {
            gameManager.cleanup();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}
