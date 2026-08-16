package com.lonleaf.chesttheft.config;

import org.bukkit.configuration.ConfigurationSection;

/** 撬锁小游戏配置：支持为不同场景创建不同的配置实例。 */
public class GameConfig {
    private final String name;
    private final int barLength;
    private final int redLength;
    private final int moveInterval;
    private final int timeoutSeconds;
    private final int cooldownSeconds;
    private final int accessDurationSeconds;
    private final int accessOnceWindowSeconds;
    private final boolean interruptDamage;
    private final double interruptMoveRange;

    private GameConfig(String name, int barLength, int redLength, int moveInterval, int timeoutSeconds,
                       int cooldownSeconds, int accessDurationSeconds, int accessOnceWindowSeconds,
                       boolean interruptDamage, double interruptMoveRange) {
        this.name = name;
        this.barLength = barLength;
        this.redLength = redLength;
        this.moveInterval = moveInterval;
        this.timeoutSeconds = timeoutSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.accessDurationSeconds = accessDurationSeconds;
        this.accessOnceWindowSeconds = accessOnceWindowSeconds;
        this.interruptDamage = interruptDamage;
        this.interruptMoveRange = interruptMoveRange;
    }

    /** 从配置段解析小游戏配置，缺失键使用默认值并做范围约束（null 表示全用默认值）。 */
    public static GameConfig from(ConfigurationSection section) {
        int barLength = Math.max(5, section == null ? 15 : section.getInt("bar-length", 15));
        int redLength = Math.max(1, Math.min(barLength - 2,
                section == null ? 3 : section.getInt("red-length", 3)));
        return new GameConfig(
                section == null ? null : section.getString("name"),
                barLength,
                redLength,
                Math.max(1, section == null ? 10 : section.getInt("move-interval", 10)),
                Math.max(1, section == null ? 15 : section.getInt("timeout", 15)),
                Math.max(0, section == null ? 3 : section.getInt("cooldown", 3)),
                Math.max(0, section == null ? 60 : section.getInt("access-duration", 60)),
                Math.max(1, section == null ? 60 : section.getInt("access-once-window", 60)),
                section == null || section.getBoolean("interrupt-damage", true),
                section == null ? 3.0 : section.getDouble("interrupt-move-range", 3.0)
        );
    }

    /** 等级名称（如 normal、advanced），未配置时为 null。 */
    public String getName() {
        return name;
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

    public int getTimeoutTicks() {
        return Math.max(1, timeoutSeconds * 20 / moveInterval);
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
}
