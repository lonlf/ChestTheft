package com.lonleaf.chesttheft.config;

import com.lonleaf.chesttheft.ChestTheft;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class PluginConfig {

    public enum DatabaseType {
        SQLITE, MYSQL
    }

    /** 战利品箱展示方式：display 使用展示实体（发包渲染），block 放置真实箱子方块。 */
    public enum LootChestDisplayType {
        DISPLAY, BLOCK
    }

    public static class DataBaseConfig {
        private final DatabaseType type;
        private final String ip;
        private final String port;
        private final String database;
        private final String user;
        private final String password;
        private final boolean useSsl;
        private final String timeZone;

        private DataBaseConfig(DatabaseType type, String ip, String port, String database,
                               String user, String password, boolean useSsl, String timeZone) {
            this.type = type;
            this.ip = ip;
            this.port = port;
            this.database = database;
            this.user = user;
            this.password = password;
            this.useSsl = useSsl;
            this.timeZone = timeZone;
        }

        public DatabaseType getType() {
            return type;
        }

        public String getIp() {
            return ip;
        }

        public String getPort() {
            return port;
        }

        public String getDatabase() {
            return database;
        }

        public String getUser() {
            return user;
        }

        public String getPassword() {
            return password;
        }

        public boolean isUseSsl() {
            return useSsl;
        }

        public String getTimeZone() {
            return timeZone;
        }
    }

    private final ChestTheft plugin;
    private final java.util.logging.Logger logger;
    private final File configFile;
    // volatile：reload 在主线程修改，其他线程（事件监听器/异步任务）读取，保证可见性
    private volatile DataBaseConfig dataBaseConfig;
    private volatile boolean debug;
    private volatile String language;
    private volatile boolean languageDetected;
    private volatile String detectedLocale;
    private volatile GameConfig gameConfig;
    private volatile boolean keyUnlockEnabled;
    private volatile boolean keyUnlockByHolder;
    private volatile boolean keyPairEnabled;
    private volatile boolean keyRepairEnabled;
    private volatile boolean keyUnpairRecipeEnabled;
    private volatile Action keyInteractionAction;
    private volatile boolean keyInteractionSneak;
    private volatile boolean keyRequireInHand;
    private volatile boolean keyParticleEnabled;
    private volatile Color keyParticleColor;
    private volatile double keyParticleRadius;
    private volatile boolean keyGlowEnabled;
    private volatile Color keyGlowColor;
    private volatile double keyGlowRadius;
    // ---- 战利品箱设置（lootchest 小节） ----
    private volatile boolean vanillaDrop;
    private volatile LootChestDisplayType lootChestDisplayType;
    private volatile boolean lootChestKeep;
    private volatile boolean lootChestDropRemaining;
    private volatile int lootChestExpireTime;
    private volatile int lootChestExpireTimeOpened;
    private volatile double lootChestInteractionRange;
    private volatile List<String> lootChestExcludedWorlds;
    private volatile boolean protectionPickingEnabled;
    private volatile double dominionSyncTimeout;
    private volatile Set<Material> lockableBlocks;
    private volatile boolean lockOwnerCloseRelock;
    private volatile boolean hopperGuardExtract;
    private volatile boolean hopperGuardInsert;
    private volatile boolean lockReadThrough;
    private volatile int lockIndexRefreshSeconds;
    private volatile boolean abortOnLockIndexLoadError;
    private volatile FontConfig fontConfig;
    private final Map<String, String> messageFormats = new HashMap<>();

    /** 最近一次 load() 是否成功；失败时内存保留旧值（首启失败则为默认值）。 */
    private volatile boolean loaded;

    public PluginConfig(ChestTheft plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        this.loaded = load();
    }

    /** 加载配置文件（含首启语言检测写回、config-version 升级）；返回是否成功，失败时保留旧值。 */
    public boolean load() {
        try {
            // 首次启动（config.yml 不存在）：按当前语言（系统检测）从 jar 内 templates/<语言>/config.yml
            // 复制一份带对应语言注释的模板，之后由 applySystemLanguage 按系统语言确认 language 项；
            // 注释语言与运行语言不联动，切换语言注释需删除 config.yml 后重新生成
            boolean firstStart = !configFile.exists();
            if (firstStart) {
                if (!TemplateFiles.saveTemplate(plugin, Messages.getCurrentLang(), "config.yml", configFile)) {
                    throw new IOException("Embedded config template not found in jar (templates/*/config.yml)");
                }
            }
            plugin.reloadConfig();

            // 自动升级：config-version 落后时把模板中缺失的键追加到文件末尾（保留用户已有键与注释）
            upgradeConfigFile();
            plugin.reloadConfig();

            FileConfiguration fileConfig = plugin.getConfig();
            if (firstStart) {
                applySystemLanguage();
                // 文本级修改 language 后重新加载，让内存配置反映新值
                plugin.reloadConfig();
                fileConfig = plugin.getConfig();
            }
            applyConfig(fileConfig);

            if (debug) {
                logger.info(Messages.getLog(Messages.LOG_CONFIG_LOADED_DEBUG,
                        dataBaseConfig.getType().name().toLowerCase(Locale.ROOT), debug, language));
            }
            loaded = true;
            return true;
        } catch (Exception e) {
            logger.severe(Messages.getLog(Messages.LOG_CONFIG_LOAD_FAIL, e.getMessage()));
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_CONFIG_KEEP_PREVIOUS), e);
            loaded = false;
            return false;
        }
    }

    /** 重载配置；返回 false 表示失败（调用方应中止后续重载并提示）。 */
    public boolean reload() {
        return load();
    }

    /** 最近一次加载是否成功。 */
    public boolean isLoaded() {
        return loaded;
    }

    /** 配置自动升级：config-version 落后时按模板追加缺失键（保留已有内容）并原子写回。 */
    private void upgradeConfigFile() {
        // 升级模板与追加注释随当前配置语言（中文/英文模板键一致，仅注释语言不同）
        String lang = plugin.getConfig().getString("language", "");
        FileConfiguration template;
        try (InputStream in = TemplateFiles.openTemplate(plugin, lang, "config.yml")) {
            if (in == null) {
                return;
            }
            template = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warning(Messages.getLog(Messages.LOG_CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        int latestVersion = template.getInt("config-version", 1);

        String raw;
        try {
            raw = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warning(Messages.getLog(Messages.LOG_CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        FileConfiguration current = YamlConfiguration.loadConfiguration(new java.io.StringReader(raw));
        int currentVersion = current.getInt("config-version", 0);
        if (currentVersion >= latestVersion) {
            return; // 已是最新或无需升级
        }

        // 收集模板中用户配置缺失的叶键（config-version 单独处理）
        FileConfiguration migrated = YamlConfiguration.loadConfiguration(new java.io.StringReader(raw));
        List<String> missing = new ArrayList<>();
        for (String key : template.getKeys(true)) {
            // language 由 applySystemLanguage 探测写回，注入模板默认值会覆盖系统语言
            if (!"config-version".equals(key) && !"language".equals(key) && !migrated.contains(key)) {
                missing.add(key);
            }
        }

        StringBuilder append = new StringBuilder();
        if (!missing.isEmpty()) {
            // 注释语言随模板语言，与配置文件已有注释保持一致
            if (TemplateFiles.templateLang(lang).equals("zh_cn")) {
                append.append("\n# --- ChestTheft 配置自动升级至 v").append(latestVersion).append("：新增配置项（默认值） ---\n");
            } else {
                append.append("\n# --- ChestTheft config upgrade to v").append(latestVersion)
                        .append(": newly added options (defaults) ---\n");
            }
            for (String key : missing) {
                append.append(key).append(": ").append(yamlValue(template.get(key))).append("\n");
            }
        }

        // 更新文件中的 config-version：已存在则替换值，否则追加
        if (Pattern.compile("(?m)^config-version:.*$").matcher(raw).find()) {
            raw = raw.replaceAll("(?m)^config-version:.*$", "config-version: " + latestVersion);
        } else {
            append.append("config-version: ").append(latestVersion).append("\n");
        }

        try {
            writeConfigAtomic(configFile, raw + append);
        } catch (Exception e) {
            logger.warning(Messages.getLog(Messages.LOG_CONFIG_UPGRADE_FAILED, e.getMessage()));
            return;
        }

        logger.info(Messages.getLog(Messages.LOG_CONFIG_UPGRADE_DONE, missing.size(), latestVersion));
    }

    /** YAML 标量序列化：字符串加引号（转义），其余原样输出。 */
    private String yamlValue(Object value) {
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }

    /** 原子写回：先写 tmp 再原子替换（防写入中断损坏），不支持原子移动时降级为普通替换。 */
    private void writeConfigAtomic(File file, String content) throws java.io.IOException {
        Path path = file.toPath();
        Path tmp = path.resolveSibling(file.getName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 首次启动时按系统语言设置 language 项：文本级替换保留注释，不重写整个文件。 */
    private void applySystemLanguage() {
        String detected = Messages.detectSystemLanguage();
        try {
            String raw = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            String updated = raw.replaceFirst("(?m)^\\s*language:.*$", "language: \"" + detected + "\"");
            writeConfigAtomic(configFile, updated);
            detectedLocale = detected;
            languageDetected = true;
        } catch (Exception e) {
            logger.warning(Messages.getLog(Messages.LOG_LANG_WRITE_FAIL, detected, e.getMessage()));
        }
    }

    /** 将内存配置解析为各字段；缺失键使用默认值。 */
    private void applyConfig(FileConfiguration config) {
        String type = config.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        DatabaseType dbType = "mysql".equals(type) ? DatabaseType.MYSQL : DatabaseType.SQLITE;
        dataBaseConfig = new DataBaseConfig(
                dbType,
                config.getString("database.ip", "localhost"),
                config.getString("database.port", "3306"),
                config.getString("database.database", "minecraft"),
                config.getString("database.user", "root"),
                config.getString("database.password", ""),
                config.getBoolean("database.usessl", false),
                config.getString("database.timezone", "UTC")
        );

        gameConfig = GameConfig.from(config.getConfigurationSection("game"));
        keyUnlockEnabled = config.getBoolean("key.unlock-enabled", true);
        keyUnlockByHolder = config.getBoolean("key.unlock-by-holder", false);
        keyPairEnabled = config.getBoolean("key.pair-enabled", true);
        keyRepairEnabled = config.getBoolean("key.repair-enabled", false);
        keyInteractionAction = parseAction(config.getString("key.interaction-action", "LEFT"));
        keyInteractionSneak = config.getBoolean("key.interaction-sneak", true);
        keyRequireInHand = config.getBoolean("key.require-in-hand", true);
        keyParticleEnabled = config.getBoolean("key.particle-enabled", true);
        Color parsedParticle = ColorParser.parse(config.getString("key.particle-color", "GOLD"));
        keyParticleColor = parsedParticle != null ? parsedParticle : Color.fromRGB(255, 215, 0);
        keyParticleRadius = Math.max(1.0, config.getDouble("key.particle-radius", 8.0));
        keyGlowEnabled = config.getBoolean("key.glow-enabled", true);
        Color parsedGlow = ColorParser.parse(config.getString("key.glow-color", "GOLD"));
        keyGlowColor = parsedGlow != null ? parsedGlow : Color.fromRGB(255, 215, 0);
        keyGlowRadius = Math.max(1.0, config.getDouble("key.glow-radius", 8.0));
        vanillaDrop = config.getBoolean("lootchest.vanilla-drop", true);
        lootChestDisplayType = parseLootChestDisplayType(config.getString("lootchest.display-type", "display"));
        lootChestKeep = config.getBoolean("lootchest.keep", true);
        lootChestDropRemaining = config.getBoolean("lootchest.drop-remaining", false);
        lootChestExpireTime = Math.max(0, config.getInt("lootchest.expire-time", 600));
        lootChestExpireTimeOpened = Math.max(0, config.getInt("lootchest.expire-time-opened", 300));
        lootChestInteractionRange = Math.max(1.0, config.getDouble("lootchest.interaction-range", 4.0));
        lootChestExcludedWorlds = config.getStringList("lootchest.worlds.exclude");
        protectionPickingEnabled = config.getBoolean("protection.picking-enabled", false);
        dominionSyncTimeout = Math.max(0.0, config.getDouble("protection.dominion-sync-timeout", 1.0));
        // 上锁方块类型列表：非法项记日志跳过；列表为空时回退默认箱子和陷阱箱，保证上锁功能可用
        lockableBlocks = new HashSet<>();
        for (String name : config.getStringList("lock.lockable-blocks")) {
            try {
                lockableBlocks.add(Material.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning(Messages.getLog(Messages.LOG_CONFIG_LOCKABLE_INVALID, name));
            }
        }
        if (lockableBlocks.isEmpty()) {
            lockableBlocks.add(Material.CHEST);
            lockableBlocks.add(Material.TRAPPED_CHEST);
        }
        lockOwnerCloseRelock = config.getBoolean("lock.owner-close-relock", false);
        hopperGuardExtract = config.getBoolean("lock.hopper-guard.extract", true);
        hopperGuardInsert = config.getBoolean("lock.hopper-guard.insert", true);
        lockReadThrough = config.getBoolean("database.read-through-locks", false);
        lockIndexRefreshSeconds = Math.max(0, config.getInt("database.lock-refresh-seconds", 30));
        abortOnLockIndexLoadError = config.getBoolean("database.abort-on-lock-load-error", true);
        // 模板键为 key-crafting-unpair-enabled；回退旧键兼容早期按代码键配置过的服务器
        keyUnpairRecipeEnabled = config.getBoolean("recipe.key-crafting-unpair-enabled",
                config.getBoolean("recipe.key-unpair-enabled", true));
        fontConfig = FontConfig.from(config.getConfigurationSection("font"));
        debug = config.getBoolean("debug", false);

        // 语言：未设置时用系统检测值兜底（首启时由 applySystemLanguage 写回文件）
        String lang = config.getString("language", "");
        language = (lang == null || lang.isBlank())
                ? Messages.detectSystemLanguage()
                : lang.toLowerCase(Locale.ROOT);

        // 各消息显示方式（message-format 小节），缺失的键保持默认 message
        messageFormats.clear();
        ConfigurationSection formatSection = config.getConfigurationSection("message-format");
        if (formatSection != null) {
            for (String key : formatSection.getKeys(false)) {
                messageFormats.put(key, formatSection.getString(key, "message"));
            }
        }
    }

    public DataBaseConfig getDataBaseConfig() {
        return dataBaseConfig;
    }

    public boolean isDebug() {
        return debug;
    }

    public String getLanguage() {
        return language;
    }

    /** 各消息显示方式配置（message-format 小节）：消息键 → message/actionbar/title/subtitle。 */
    public Map<String, String> getMessageFormats() {
        return messageFormats;
    }

    /** 本次启动是否发生了语言自动检测（已写入 config.yml）。 */
    public boolean isLanguageDetected() {
        return languageDetected;
    }

    /** 首次生成配置时检测到的系统地区语言，未检测时为 null。 */
    public String getDetectedLocale() {
        return detectedLocale;
    }

    /** 小游戏配置（game 小节）。 */
    public GameConfig getGameConfig() {
        return gameConfig;
    }

    public boolean isKeyUnlockEnabled() {
        return keyUnlockEnabled;
    }

    /** 非上锁者手持配对钥匙是否可卸锁（key.unlock-by-holder）；false 时仅上锁者本人可卸锁。 */
    public boolean isKeyUnlockByHolder() {
        return keyUnlockByHolder;
    }

    public boolean isLockable(Material material) {
        return material != null && lockableBlocks.contains(material);
    }

    /** 锁被撬开后，所有者关闭箱子时是否立即重新上锁（lock.owner-close-relock）。 */
    public boolean isLockOwnerCloseRelock() {
        return lockOwnerCloseRelock;
    }

    /** 漏斗守卫：拦截从已上锁容器取出物品（默认开）。 */
    public boolean isHopperGuardExtract() {
        return hopperGuardExtract;
    }

    /** 漏斗守卫：拦截向已上锁容器送入物品（默认开）。 */
    public boolean isHopperGuardInsert() {
        return hopperGuardInsert;
    }

    /** 多服共享同一数据库时是否定时全量刷新锁索引（默认关，单服内存权威）。 */
    public boolean isLockReadThrough() {
        return lockReadThrough;
    }

    /** 锁索引刷新周期（秒），read-through 开启时生效；0 表示不刷新。 */
    public int getLockIndexRefreshSeconds() {
        return lockIndexRefreshSeconds;
    }

    /** 启动载入锁索引失败时是否中止启用（默认是；false 则降级为"全部视为已上锁"）。 */
    public boolean isAbortOnLockIndexLoadError() {
        return abortOnLockIndexLoadError;
    }

    /** 是否允许钥匙配对功能（未配对钥匙 + 交互按键，由上锁者为钥匙配对）。 */
    public boolean isKeyPairEnabled() {
        return keyPairEnabled;
    }

    /** 是否允许已配对的钥匙重新配对到另一把锁（key.repair-enabled）；需两次交互确认。 */
    public boolean isKeyRepairEnabled() {
        return keyRepairEnabled;
    }

    /** 是否开启已配对钥匙合成未配对钥匙配方（recipe.key-crafting-unpair-enabled）。 */
    public boolean isKeyUnpairRecipeEnabled() {
        return keyUnpairRecipeEnabled;
    }

    public Action getKeyInteractionAction() {
        return keyInteractionAction;
    }

    public boolean isKeyInteractionSneak() {
        return keyInteractionSneak;
    }

    /** 打开上锁箱子是否必须手持钥匙，false 时背包中有钥匙即可。 */
    public boolean isKeyRequireInHand() {
        return keyRequireInHand;
    }

    /** 手持匹配钥匙时对应箱子粒子提示（key.particle-enabled）。 */
    public boolean isKeyParticleEnabled() {
        return keyParticleEnabled;
    }

    /** 钥匙粒子颜色。 */
    public Color getKeyParticleColor() {
        return keyParticleColor;
    }

    /** 钥匙粒子生效半径（方块）。 */
    public double getKeyParticleRadius() {
        return keyParticleRadius;
    }

    /** 手持匹配钥匙时对应箱子发光提示（key.glow-enabled）。 */
    public boolean isKeyGlowEnabled() {
        return keyGlowEnabled;
    }

    /** 钥匙发光颜色。 */
    public Color getKeyGlowColor() {
        return keyGlowColor;
    }

    /** 钥匙发光生效半径（方块）。 */
    public double getKeyGlowRadius() {
        return keyGlowRadius;
    }

    /** 生物死亡是否保持原版掉落（lootchest.vanilla-drop）；false 时将掉落物转化为战利品箱。 */
    public boolean isVanillaDrop() {
        return vanillaDrop;
    }

    public LootChestDisplayType getLootChestDisplayType() {
        return lootChestDisplayType;
    }

    /** 玩家未拿完战利品时是否保留箱子（取空后自动消失）。 */
    public boolean isLootChestKeep() {
        return lootChestKeep;
    }

    /** keep=false 时未取完的剩余物品是否掉落到箱子位置。 */
    public boolean isLootChestDropRemaining() {
        return lootChestDropRemaining;
    }

    /** 箱子未被开启的过期时间（秒），0 表示不过期。 */
    public int getLootChestExpireTime() {
        return lootChestExpireTime;
    }

    /** 箱子被开启后的过期时间（秒），0 表示不过期。 */
    public int getLootChestExpireTimeOpened() {
        return lootChestExpireTimeOpened;
    }

    /** 与展示实体的交互判定距离（display 模式，方块）。 */
    public double getLootChestInteractionRange() {
        return lootChestInteractionRange;
    }

    /** 不生成战利品箱的世界名列表。 */
    public List<String> getLootChestExcludedWorlds() {
        return lootChestExcludedWorlds;
    }

    /** 受保护插件保护的箱子是否启用撬锁；false 时不可上锁，已上锁由保护所有者交互时自动卸锁。 */
    public boolean isProtectionPickingEnabled() {
        return protectionPickingEnabled;
    }

    /** Dominion 异步授权同步等待超时（秒；0 表示不阻塞，超时即放弃授权并回滚）。 */
    public double getDominionSyncTimeout() {
        return dominionSyncTimeout;
    }

    /** 位图渲染字体配置（font 小节）：偏移字体/位图字体与码位。 */
    public FontConfig getFontConfig() {
        return fontConfig;
    }

    /** 解析战利品箱展示方式：display / block，非法值回退为 display。 */
    private LootChestDisplayType parseLootChestDisplayType(String name) {
        if (name == null) {
            return LootChestDisplayType.DISPLAY;
        }
        try {
            return LootChestDisplayType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LootChestDisplayType.DISPLAY;
        }
    }

    /** 解析钥匙交互按键动作：LEFT / RIGHT 对应点击方块事件，非法值回退为 LEFT。 */
    private Action parseAction(String name) {
        if (name == null) {
            return Action.LEFT_CLICK_BLOCK;
        }
        switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "LEFT":
                return Action.LEFT_CLICK_BLOCK;
            case "RIGHT":
                return Action.RIGHT_CLICK_BLOCK;
            default:
                try {
                    return Action.valueOf(name.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return Action.LEFT_CLICK_BLOCK;
                }
        }
    }
}
