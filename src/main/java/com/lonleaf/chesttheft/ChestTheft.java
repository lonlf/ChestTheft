package com.lonleaf.chesttheft;

import com.lonleaf.chesttheft.command.CommandManager;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.database.DatabaseManager;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemTagger;
import com.lonleaf.chesttheft.listener.ChestListener;
import com.lonleaf.chesttheft.listener.KeyGlowListener;
import com.lonleaf.chesttheft.lootchest.LootChestConfigManager;
import com.lonleaf.chesttheft.lootchest.LootChestListener;
import com.lonleaf.chesttheft.lootchest.LootChestManager;
import com.lonleaf.chesttheft.message.BitmapCalculator;
import com.lonleaf.chesttheft.message.OffsetChars;
import com.lonleaf.chesttheft.packet.PacketManager;
import com.lonleaf.chesttheft.protection.ProtectionListener;
import com.lonleaf.chesttheft.service.ChestService;
import com.lonleaf.chesttheft.service.KeyGlowTask;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.BlockDisplayMeta;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChestTheft extends JavaPlugin {

    private DatabaseManager databaseManager;
    private GameManager gameManager;
    private LockConfigManager lockConfigManager;
    private LootChestManager lootChestManager;
    private KeyGlowTask keyGlowTask;
    private ProtectionListener protectionListener;

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
        // EntityLib 初始化：基于 PacketEvents 的纯客户端实体库（shade 重定位为 com.lonleaf.entitylib）
        EntityLib.init(new SpigotEntityLibPlatform(this),
                new APIConfig(PacketEvents.getAPI()).usePlatformLogger());
        EntityLib.getApi().onEnable();
        // 临时调试：确认 PacketEvents 版本检测与 EntityLib Display 布局选择（排查 display 箱子/发光实体不可见）
        getLogger().info("[ChestTheft][DEBUG] PacketEvents ServerVersion = "
                + PacketEvents.getAPI().getServerManager().getVersion());
        getLogger().info("[ChestTheft][DEBUG] AbstractDisplayMeta.MAX_OFFSET = " + AbstractDisplayMeta.MAX_OFFSET
                + " (22=旧布局/<1.20.2, 23=新布局/>=1.20.2), BlockDisplayMeta.OFFSET = " + BlockDisplayMeta.OFFSET);
        // 配置管理器：加载全部配置（含首启语言检测写回）
        PluginConfig config = new PluginConfig(this);
        // 应用 config.yml 中 message-format 小节的消息显示方式配置
        Messages.applyFormats(config.getMessageFormats());
        if (config.isLanguageDetected()) {
            getLogger().info(Messages.getLog(Messages.LOG_LANG_DETECTED, config.getDetectedLocale(), config.getLanguage()));
        }
        // 位图渲染：初始化偏移字符工具与位图计算工具（字体/码位/资源包参数配置，材质包由服主自行准备与分发）
        OffsetChars.init(config.getFontConfig());
        BitmapCalculator.init(config.getFontConfig());

        databaseManager = new DatabaseManager(this, config);
        ChestService chestService = new ChestService(databaseManager.getDatabase());
        ItemTagger itemTagger = new ItemTagger(new NamespacedKey(this, "item_id"),
                new NamespacedKey(this, "paired_lock"), new NamespacedKey(this, "paired_token"),
                new NamespacedKey(this, "lock_trigger"));
        ItemConfigManager itemConfigManager = new ItemConfigManager(this);
        ItemManager itemManager = new ItemManager(itemTagger, itemConfigManager);
        // 协议包模块：为特殊物品动态注入 Lore 展示信息（类型、配对状态等）
        new PacketManager(this, config, itemConfigManager);
        // 不同等级锁的小游戏配置（gamelevel/lock.yml）；等级 0 固定为 config.yml 的 game 小节默认配置
        lockConfigManager = new LockConfigManager(this, config.getGameConfig());
        // 触发器系统：加载 trigger 文件夹配置，在撬锁成功/失败/取消/打断、上锁、钥匙开锁/配对时执行配置动作
        TriggerManager triggerManager = new TriggerManager(this);
        // 注册物品定义中的内嵌触发器为临时 id（def:<物品ID>:<触发器键>），动作只进内存注册表
        triggerManager.syncItemTriggers(itemConfigManager.getDefinitions());
        gameManager = new GameManager(this, config.getGameConfig(), triggerManager, chestService, itemManager);

        getServer().getPluginManager().registerEvents(new ChestListener(chestService, gameManager, itemManager, config, lockConfigManager, triggerManager), this);
        // 保护监听：监听本插件核心操作事件（受保护箱子阻止上锁/交互、保护所有者接管卸锁），
        // 并订阅 LWC/Bolt 保护创建回调（已上锁箱子被保护且关闭撬锁时自动卸锁）
        protectionListener = new ProtectionListener(this, config, chestService, itemManager, databaseManager.getDatabase());
        protectionListener.register();
        getServer().getPluginManager().registerEvents(protectionListener, this);
        // 小游戏管理器监听：撬锁中受击或移动超范围时按配置中止游戏
        getServer().getPluginManager().registerEvents(gameManager, this);
        // 钥匙发光提示：手持匹配钥匙时对应箱子发光（key.glow-* 配置）
        keyGlowTask = new KeyGlowTask(this, config, chestService, itemManager);
        keyGlowTask.start();
        // 发光与箱子开合联动：打开时移除发光实体（避免开盖动画错位），关闭后恢复
        getServer().getPluginManager().registerEvents(new KeyGlowListener(keyGlowTask), this);

        // 战利品箱模块：生物死亡掉落转化为战利品箱（lootchest/ 配置文件夹）
        LootChestConfigManager lootChestConfigManager = new LootChestConfigManager(this, itemManager);
        lootChestManager = new LootChestManager(this, config, lootChestConfigManager);
        LootChestListener lootChestListener = new LootChestListener(this, config, lootChestManager, lootChestConfigManager, gameManager, lockConfigManager);
        getServer().getPluginManager().registerEvents(lootChestListener, this);

        new CommandManager(this, itemManager, itemTagger, itemConfigManager, config, gameManager, lockConfigManager, triggerManager, lootChestManager);

        getLogger().info(Messages.getLog(Messages.LOG_ENABLED));
    }

    @Override
    public void onDisable() {
        // 先清理 EntityLib 纯客户端实体（发包销毁需在 PacketEvents terminate 之前完成）
        if (gameManager != null) {
            gameManager.cleanup();
        }
        if (lootChestManager != null) {
            lootChestManager.clearAll();
        }
        // 移除钥匙发光展示实体（EntityLib 实体，须在 PacketEvents terminate 之前清理）
        if (keyGlowTask != null) {
            keyGlowTask.cancel();
        }
        if (protectionListener != null) {
            protectionListener.unregister();
        }
        PacketEvents.getAPI().terminate();
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}
