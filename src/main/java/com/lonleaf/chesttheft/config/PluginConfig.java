package com.lonleaf.chesttheft.config;

import com.lonleaf.chesttheft.ChestTheft;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class PluginConfig {

    public enum DatabaseType {
        SQLITE, MYSQL
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
    private volatile int barLength;
    private volatile int redLength;
    private volatile int moveInterval;
    private volatile int timeoutSeconds;
    private volatile int cooldownSeconds;
    private volatile boolean keyUnlockEnabled;
    private volatile boolean keyPairEnabled;
    private volatile Action keyInteractionAction;
    private volatile boolean keyInteractionSneak;
    private volatile boolean keyRequireInHand;
    /** 各消息显示方式配置（message-format 小节）：消息键 → message/actionbar/title/subtitle。 */
    private final Map<String, String> messageFormats = new HashMap<>();

    public PluginConfig(ChestTheft plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        load();
    }

    /**
     * 加载配置文件；saveDefaultConfig 会自动释放资源中的 config.yml。
     * 配置加载失败时保留旧配置继续运行，不让插件加载失败。
     */
    public void load() {
        try {
            // 首次启动（config.yml 不存在）：saveDefaultConfig 复制模板后按系统语言设置 language 项
            boolean firstStart = !configFile.exists();
            plugin.saveDefaultConfig();
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
        } catch (Exception e) {
            logger.severe(Messages.getLog(Messages.LOG_CONFIG_LOAD_FAIL, e.getMessage()));
            logger.log(Level.SEVERE, "Config load failure, keeping previous config", e);
        }
    }

    /** 重载配置（reload 命令调用）。 */
    public void reload() {
        load();
    }

    /**
     * 配置自动升级：以 jar 内模板为准，若文件中 config-version 低于模板版本，
     * 先将模板中缺失的键以扁平键形式追加到文件末尾（不重写已有内容，保留注释），
     * 更新 config-version 并原子写回（tmp + ATOMIC_MOVE，避免写入中断损坏文件）。
     * 文件内容不匹配模板时由 applyConfig 的默认值兜底。
     */
    private void upgradeConfigFile() {
        FileConfiguration template;
        try (InputStream in = plugin.getResource("config.yml")) {
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
            if (!"config-version".equals(key) && !migrated.contains(key)) {
                missing.add(key);
            }
        }

        StringBuilder append = new StringBuilder();
        if (!missing.isEmpty()) {
            append.append("\n# --- ChestTheft config upgrade to v").append(latestVersion)
                    .append(": newly added options (defaults) ---\n");
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

    /**
     * 原子写回配置文件：先写入同名 .tmp 文件，再原子替换目标文件，
     * 避免写入中断（磁盘满/进程崩溃）导致配置文件损坏。
     * 文件系统不支持原子移动时降级为普通替换。
     */
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
            logger.warning("Failed to write language to config, keeping default"); // 兜底日志（消息未就绪时仍可诊断）
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

        barLength = Math.max(5, config.getInt("game.bar-length", 15));
        redLength = Math.max(1, Math.min(barLength - 2, config.getInt("game.red-length", 3)));
        moveInterval = Math.max(1, config.getInt("game.move-interval", 10));
        timeoutSeconds = Math.max(1, config.getInt("game.timeout", 15));
        cooldownSeconds = Math.max(0, config.getInt("game.cooldown", 3));
        keyUnlockEnabled = config.getBoolean("key.unlock-enabled", true);
        keyPairEnabled = config.getBoolean("key.pair-enabled", true);
        keyInteractionAction = parseAction(config.getString("key.interaction-action", "LEFT"));
        keyInteractionSneak = config.getBoolean("key.interaction-sneak", true);
        keyRequireInHand = config.getBoolean("key.require-in-hand", true);
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

    public int getBarLength() {
        return barLength;
    }

    public int getRedLength() {
        return redLength;
    }

    public int getMoveInterval() {
        return moveInterval;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** 换算为小游戏更新次数。 */
    public int getTimeoutTicks() {
        return Math.max(1, timeoutSeconds * 20 / moveInterval);
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean isKeyUnlockEnabled() {
        return keyUnlockEnabled;
    }

    /** 是否允许钥匙配对功能（未配对钥匙 + 交互按键，由上锁者为钥匙配对）。 */
    public boolean isKeyPairEnabled() {
        return keyPairEnabled;
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
