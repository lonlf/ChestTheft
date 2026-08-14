package com.lonleaf.chesttheft.config;

import com.lonleaf.chesttheft.ChestTheft;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.block.Action;

import java.util.Locale;

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
    private DataBaseConfig dataBaseConfig;
    private boolean debug;
    private String language;
    private int barLength;
    private int redLength;
    private int moveInterval;
    private int timeoutSeconds;
    private int cooldownSeconds;
    private boolean keyUnlockEnabled;
    private boolean keyPairEnabled;
    private Action keyInteractionAction;
    private boolean keyInteractionSneak;
    private boolean keyRequireInHand;

    public PluginConfig(ChestTheft plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        String type = config.getString("DataBase.type", "sqlite").toLowerCase(Locale.ROOT);
        DatabaseType dbType = "mysql".equals(type) ? DatabaseType.MYSQL : DatabaseType.SQLITE;
        dataBaseConfig = new DataBaseConfig(
                dbType,
                config.getString("DataBase.ip", "localhost"),
                config.getString("DataBase.port", "3306"),
                config.getString("DataBase.database", "minecraft"),
                config.getString("DataBase.user", "root"),
                config.getString("DataBase.password", ""),
                config.getBoolean("DataBase.useSSL", false),
                config.getString("DataBase.timeZone", "UTC")
        );

        barLength = Math.max(5, config.getInt("Game.bar-length", 15));
        redLength = Math.max(1, Math.min(barLength - 2, config.getInt("Game.red-length", 3)));
        moveInterval = Math.max(1, config.getInt("Game.move-interval", 10));
        timeoutSeconds = Math.max(1, config.getInt("Game.timeout", 15));
        cooldownSeconds = Math.max(0, config.getInt("Game.cooldown", 3));
        keyUnlockEnabled = config.getBoolean("Key.unlock-enabled", true);
        keyPairEnabled = config.getBoolean("Key.pair-enabled", true);
        keyInteractionAction = parseAction(config.getString("Key.interaction-action", "LEFT"));
        keyInteractionSneak = config.getBoolean("Key.interaction-sneak", true);
        keyRequireInHand = config.getBoolean("Key.require-in-hand", true);
        debug = config.getBoolean("Debug", false);
        ensureLanguage(config);
        language = config.getString("language", "en_gb").toLowerCase(Locale.ROOT);
    }

    private boolean languageDetected;
    private String detectedLocale;

    public DataBaseConfig getDataBaseConfig() {
        return dataBaseConfig;
    }

    public boolean isDebug() {
        return debug;
    }

    public String getLanguage() {
        return language;
    }

    /** 生成配置时自动填充语言：language 缺失时按服务器地区码写入 config.yml。 */
    private void ensureLanguage(FileConfiguration config) {
        if (config.getString("language") == null) {
            detectedLocale = Messages.detectSystemLanguage();
            config.set("language", detectedLocale);
            plugin.saveConfig();
            languageDetected = true;
        }
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
