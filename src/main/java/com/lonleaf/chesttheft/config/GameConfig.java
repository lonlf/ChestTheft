package com.lonleaf.chesttheft.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 小游戏公共配置：会话级参数（超时/冷却/开箱授权/中断规则）与所选小游戏类型。
 * 各玩法特有参数（如 moving-bar 的 bar-length）由玩法自己的配置类从原始配置段解析。
 */
public class GameConfig {
    private final String name;
    /** 小游戏类型（配置 game-type），决定创建哪种玩法会话。 */
    private final String gameType;
    private final int timeoutSeconds;
    private final int cooldownSeconds;
    private final int accessDurationSeconds;
    private final int accessOnceWindowSeconds;
    private final boolean interruptDamage;
    private final double interruptMoveRange;
    /** 撬锁成功音效（所有玩法通用）。 */
    private final SoundConfig successSound;
    /** 撬锁失败音效（所有玩法通用）。 */
    private final SoundConfig failSound;
    /** 原始配置段：玩法特有参数由各玩法配置类解析；null 表示全部使用默认值。 */
    private final ConfigurationSection section;

    private GameConfig(String name, String gameType, int timeoutSeconds, int cooldownSeconds,
                       int accessDurationSeconds, int accessOnceWindowSeconds,
                       boolean interruptDamage, double interruptMoveRange,
                       SoundConfig successSound, SoundConfig failSound, ConfigurationSection section) {
        this.name = name;
        this.gameType = gameType;
        this.timeoutSeconds = timeoutSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.accessDurationSeconds = accessDurationSeconds;
        this.accessOnceWindowSeconds = accessOnceWindowSeconds;
        this.interruptDamage = interruptDamage;
        this.interruptMoveRange = interruptMoveRange;
        this.successSound = successSound;
        this.failSound = failSound;
        this.section = section;
    }

    /** 从配置段解析小游戏公共配置，缺失键使用默认值并做范围约束（null 表示全用默认值）。
     *  等级配置通常已由 LockConfigManager 与默认配置合并，缺失键即继承默认配置的值。 */
    public static GameConfig from(ConfigurationSection section) {
        ConfigurationSection sounds = section == null ? null : section.getConfigurationSection("sounds");
        return new GameConfig(
                section == null ? null : section.getString("name"),
                section == null ? "moving-bar" : section.getString("game-type", "moving-bar"),
                Math.max(1, section == null ? 15 : section.getInt("timeout", 15)),
                Math.max(0, section == null ? 3 : section.getInt("cooldown", 3)),
                Math.max(0, section == null ? 60 : section.getInt("access-duration", 60)),
                Math.max(1, section == null ? 60 : section.getInt("access-once-window", 60)),
                section == null || section.getBoolean("interrupt-damage", true),
                section == null ? 3.0 : section.getDouble("interrupt-move-range", 3.0),
                SoundConfig.from(sounds == null ? null : sounds.getConfigurationSection("success")),
                SoundConfig.from(sounds == null ? null : sounds.getConfigurationSection("fail")),
                section
        );
    }

    /** 等级名称（如 normal、advanced），未配置时为 null。 */
    public String getName() {
        return name;
    }

    /** 小游戏类型（配置 game-type），决定创建哪种玩法会话。 */
    public String getGameType() {
        return gameType;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /** 撬锁成功后玩家可自由打开该箱子的时长（秒）；0 表示不启用限时授权，改为仅一次打开机会。 */
    public int getAccessDurationSeconds() {
        return accessDurationSeconds;
    }

    /** 一次性打开机会的有效窗口（秒）：access-duration 为 0 时生效。 */
    public int getAccessOnceWindowSeconds() {
        return accessOnceWindowSeconds;
    }

    /** 撬锁中受到伤害是否中断撬锁。 */
    public boolean isInterruptDamage() {
        return interruptDamage;
    }

    /** 撬锁中移动超过该范围（方块）中断撬锁；0 或负值表示不限制移动。 */
    public double getInterruptMoveRange() {
        return interruptMoveRange;
    }

    /** 撬锁成功音效（所有玩法通用）。 */
    public SoundConfig getSuccessSound() {
        return successSound;
    }

    /** 撬锁失败音效（所有玩法通用）。 */
    public SoundConfig getFailSound() {
        return failSound;
    }

    /** 原始配置段：玩法特有参数由各玩法配置类解析；null 表示全部使用默认值。 */
    public ConfigurationSection getSection() {
        return section;
    }
}
