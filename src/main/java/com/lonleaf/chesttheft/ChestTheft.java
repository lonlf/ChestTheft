package com.lonleaf.chesttheft;

import com.lonleaf.chesttheft.command.CommandManager;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.database.LockStore;
import com.lonleaf.chesttheft.database.DatabaseManager;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemTagger;
import com.lonleaf.chesttheft.listener.ChestListener;
import com.lonleaf.chesttheft.listener.HopperGuardListener;
import com.lonleaf.chesttheft.listener.KeyGlowListener;
import com.lonleaf.chesttheft.lootchest.LootChestConfigManager;
import com.lonleaf.chesttheft.lootchest.LootChestListener;
import com.lonleaf.chesttheft.lootchest.LootChestManager;
import com.lonleaf.chesttheft.message.BitmapCalculator;
import com.lonleaf.chesttheft.message.OffsetChars;
import com.lonleaf.chesttheft.packet.PacketManager;
import com.lonleaf.chesttheft.protection.ProtectionManager;
import com.lonleaf.chesttheft.recipe.RecipeManager;
import com.lonleaf.chesttheft.service.ChestService;
import com.lonleaf.chesttheft.service.KeyGlowTask;
import com.lonleaf.chesttheft.service.ResourcePackExporter;
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
import org.bukkit.scheduler.BukkitTask;

public final class ChestTheft extends JavaPlugin {

    private DatabaseManager databaseManager;
    private GameManager gameManager;
    private LockConfigManager lockConfigManager;
    private LootChestManager lootChestManager;
    private KeyGlowTask keyGlowTask;
    private ProtectionManager protectionManager;
    private RecipeManager recipeManager;
    private BukkitTask lockIndexTask;

    @Override
    public void onLoad() {
        // 初始化 PacketEvents（须在 onEnable 前完成，之后经 PacketEvents.getAPI() 使用）
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        // 加载（build() 仅创建实例，必须显式 load() 解析版本/初始化反射）
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        // 注意：首启配置生成与语言检测写回统一由 PluginConfig.load() 处理（按语言从 jar 内模板复制）；
        // 若在此提前 saveDefaultConfig() 会误判 firstStart，导致模板复制与 applySystemLanguage() 永不执行。

        // 预读语言：未设置时用系统检测值，先行初始化消息系统（供后续配置解析与日志使用）
        String lang = getConfig().getString("language", "");
        if (lang == null || lang.isBlank()) {
            lang = Messages.detectSystemLanguage();
        }
        Messages.init(getDataFolder().toPath(), lang);

        // PacketEvents 注册内部监听器并完成通道注入（须在注册包监听器之前）
        PacketEvents.getAPI().getSettings().reEncodeByDefault(true);
        PacketEvents.getAPI().init();
        // EntityLib 初始化：基于 PacketEvents 的纯客户端实体库（shade 重定位为 com.lonleaf.entitylib）
        EntityLib.init(new SpigotEntityLibPlatform(this),
                new APIConfig(PacketEvents.getAPI()).usePlatformLogger());
        EntityLib.getApi().onEnable();
        // 环境信息（仅 debug:true 时输出）
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[ChestTheft][DEBUG] PacketEvents ServerVersion = "
                    + PacketEvents.getAPI().getServerManager().getVersion());
            getLogger().info("[ChestTheft][DEBUG] AbstractDisplayMeta.MAX_OFFSET = " + AbstractDisplayMeta.MAX_OFFSET
                    + " (22=旧布局/<1.20.2, 23=新布局/>=1.20.2), BlockDisplayMeta.OFFSET = " + BlockDisplayMeta.OFFSET);
        }
        // 配置管理器：加载全部配置（含首启语言检测写回）
        PluginConfig config = new PluginConfig(this);
        if (!config.isLoaded()) {
            // 首启配置加载失败：继续运行会以空配置触发 NPE 连锁，直接不启用更清晰
            getLogger().severe(Messages.getLog(Messages.LOG_CONFIG_ABORT_ENABLE));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 应用 config.yml 中 message-format 小节的消息显示方式配置
        Messages.applyFormats(config.getMessageFormats());
        if (config.isLanguageDetected()) {
            getLogger().info(Messages.getLog(Messages.LOG_LANG_DETECTED, config.getDetectedLocale(), config.getLanguage()));
        }
        // 位图渲染：初始化偏移字符与位图计算工具（字体/码位/资源包参数配置）
        OffsetChars.init(config.getFontConfig());
        BitmapCalculator.init(config.getFontConfig());
        // 材质包：jar 内 zip 释放到插件目录（已存在则跳过，不覆盖服主自改版本）
        ResourcePackExporter.exportIfMissing(this);

        databaseManager = new DatabaseManager(this, config);
        // 锁状态内存索引：启动全量载入（失败默认中止启用，避免"锁状态未知"下放行）
        LockStore lockStore = new LockStore(databaseManager.getDatabase(), getLogger());
        lockStore.loadAll(config.isAbortOnLockIndexLoadError());
        ChestService chestService = new ChestService(lockStore);
        if (config.isLockReadThrough() && config.getLockIndexRefreshSeconds() > 0) {
            long period = 20L * config.getLockIndexRefreshSeconds();
            lockIndexTask = getServer().getScheduler().runTaskTimer(this, lockStore::refresh, period, period);
        }
        ItemTagger itemTagger = new ItemTagger(new NamespacedKey(this, "item_id"),
                new NamespacedKey(this, "paired_lock"), new NamespacedKey(this, "paired_token"),
                new NamespacedKey(this, "lock_trigger"));
        ItemConfigManager itemConfigManager = new ItemConfigManager(this);
        ItemManager itemManager = new ItemManager(itemTagger, itemConfigManager);
        // 协议包模块：为特殊物品动态注入 Lore 展示信息（类型、配对状态等）
        new PacketManager(this, config, itemConfigManager);
        // 不同等级锁的小游戏配置（gamelevel/locklevel.yml）；等级 0 固定为 config.yml 的 game 小节默认配置
        lockConfigManager = new LockConfigManager(this, config.getGameConfig());
        // 触发器系统：加载 trigger 文件夹配置，在撬锁成功/失败/取消/打断、上锁、钥匙开锁/配对时执行配置动作
        TriggerManager triggerManager = new TriggerManager(this);
        // 注册物品定义中的内嵌触发器为临时 id（def:<物品ID>:<触发器键>），动作只进内存注册表
        triggerManager.syncItemTriggers(itemConfigManager.getDefinitions());
        gameManager = new GameManager(this, config.getGameConfig(), triggerManager, chestService, itemManager);

        getServer().getPluginManager().registerEvents(new ChestListener(chestService, gameManager, itemManager, config, lockConfigManager, triggerManager), this);
        // 保护兼容统一入口：监听核心操作事件并调度各保护适配器（临时授权/撤销/保护所有者接管卸锁）
        protectionManager = new ProtectionManager(this, config, chestService, itemManager, databaseManager.getDatabase());
        protectionManager.register();
        // 小游戏管理器监听：撬锁中受击或移动超范围时按配置中止游戏
        getServer().getPluginManager().registerEvents(gameManager, this);
        // 钥匙发光提示：手持匹配钥匙时对应箱子发光（key.glow-* 配置）
        keyGlowTask = new KeyGlowTask(this, config, chestService, itemManager);
        keyGlowTask.start();
        // 发光与箱子开合联动：打开时移除发光实体（避免开盖动画错位），关闭后恢复
        getServer().getPluginManager().registerEvents(new KeyGlowListener(keyGlowTask), this);

        // 配方管理：注册自定义合成配方（已配对钥匙 → 未配对钥匙），监听合成事件
        recipeManager = new RecipeManager(this, config, itemManager, itemConfigManager);
        recipeManager.registerRecipes();
        getServer().getPluginManager().registerEvents(recipeManager, this);

        // 战利品箱模块：生物死亡掉落转化为战利品箱（lootchest/ 配置文件夹）
        LootChestConfigManager lootChestConfigManager = new LootChestConfigManager(this, itemManager);
        lootChestManager = new LootChestManager(this, config, lootChestConfigManager);
        LootChestListener lootChestListener = new LootChestListener(this, config, lootChestManager, lootChestConfigManager, gameManager, lockConfigManager);
        getServer().getPluginManager().registerEvents(lootChestListener, this);
        // 补扫已加载区块：block 模式箱子在重启后不会触发 ChunkLoadEvent（出生点等区块已加载）
        lootChestManager.scanLoadedChunks();
        // 漏斗守卫：拦截以已上锁容器/战利品箱为来源或目标的物品搬运（漏斗、漏斗矿车）
        getServer().getPluginManager().registerEvents(
                new HopperGuardListener(this, config, chestService, lootChestManager), this);

        new CommandManager(this, itemManager, itemTagger, itemConfigManager, config, gameManager, lockConfigManager, triggerManager, lootChestManager, recipeManager);

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
        if (lockIndexTask != null) {
            lockIndexTask.cancel();
        }
        if (protectionManager != null) {
            protectionManager.unregister();
        }
        // 移除自定义合成配方
        if (recipeManager != null) {
            recipeManager.unregisterRecipes();
        }
        // EntityLib 无 onDisable：兜底清空残余客户端实体，须在 PacketEvents terminate 之前
        try {
            EntityLib.getApi().getDefaultContainer().clearEntities();
        } catch (Throwable t) {
            getLogger().warning("[ChestTheft] EntityLib cleanup failed: " + t.getMessage());
        }
        PacketEvents.getAPI().terminate();
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
}
