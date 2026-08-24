package com.lonleaf.chesttheft.minigame.tumblerbar;

import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * 机关转轮小游戏（tumbler-bar）特有配置：A/B 状态数、可解锁状态、位置、尝试次数与渲染方式。
 * A/D 调 A（边界钳制），W 转动 B；仅 A 处于可解锁状态时 B 可达最终状态。
 */
public class TumblerBarConfig {

    /** 机关 A 普通状态数（每个状态另有对应特殊状态，尝试失败时显示）。 */
    private final int aStates;
    /** 尝试失败复位后 A 的初始状态。 */
    private final int initialA;
    /** 可解锁的 A 状态：仅 A 处于该状态时 B 可达最终状态。 */
    private final int unlockA;
    /** 机关 B 状态数（状态从 0 计）。 */
    private final int bStates;
    /** B 的最终状态：B 达到该状态即撬锁成功。 */
    private final int bFinal;
    /** A 左上角相对背景左端的水平像素位置。 */
    private final int aOffset;
    /** B 左上角相对背景左端的水平像素位置。 */
    private final int bOffset;
    /** 背景总长度（格，位图模式下每格 = 切片宽像素）。 */
    private final int backgroundLength;
    /** 最大尝试次数：累计失败达到后判定撬锁失败。 */
    private final int maxAttempts;
    /** A/D 调 A 的步进间隔（tick，20 tick = 1 秒）。 */
    private final int moveInterval;
    /** 机关 A/B 状态图宽度（格，1 格 = 切片宽像素；位图模式下用于分段定位计算）。 */
    private final int tumblerWidth;
    /** 渲染方式：ascii（默认，无需材质包）/ bitmap（需安装材质包）。 */
    private final String renderMode;

    private TumblerBarConfig(int aStates, int initialA, int unlockA, int bStates, int bFinal,
                             int aOffset, int bOffset, int backgroundLength,
                             int maxAttempts, int moveInterval, int tumblerWidth, String renderMode) {
        this.aStates = aStates;
        this.initialA = initialA;
        this.unlockA = unlockA;
        this.bStates = bStates;
        this.bFinal = bFinal;
        this.aOffset = aOffset;
        this.bOffset = bOffset;
        this.backgroundLength = backgroundLength;
        this.maxAttempts = maxAttempts;
        this.moveInterval = moveInterval;
        this.tumblerWidth = tumblerWidth;
        this.renderMode = renderMode;
    }

    /** 从配置段解析玩法特有参数，缺失键使用默认值并做范围约束（null 表示全用默认值）。 */
    public static TumblerBarConfig from(ConfigurationSection section) {
        return from(section, null);
    }

    /** 从配置段解析玩法特有参数（logger 用于输出 b-final 钳制告警，可为 null）。 */
    public static TumblerBarConfig from(ConfigurationSection section, Logger logger) {
        int aStates = Math.max(2, section == null ? 4 : section.getInt("a-states", 4));
        int initialA = clamp(0, aStates - 1, section == null ? 0 : section.getInt("initial-a", 0));
        int unlockA = clamp(0, aStates - 1, section == null ? 1 : section.getInt("unlock-a", 1));
        int bStates = Math.max(2, section == null ? 5 : section.getInt("b-states", 5));
        int bFinal;
        if (section == null || !section.contains("b-final")) {
            bFinal = bStates - 1;
        } else {
            bFinal = clamp(1, bStates - 1, section.getInt("b-final"));
            // 成功条件是 A 对准时 B 可达 b-states-1 且与 b-final 相等：b-final 取其他值时游戏永远无法胜利，钳制并告警
            if (bFinal != bStates - 1) {
                if (logger != null) {
                    logger.warning(Messages.getLog(Messages.LOG_TUMBLER_BFINAL_INVALID, bStates - 1));
                }
                bFinal = bStates - 1;
            }
        }
        int aOffset = Math.max(0, section == null ? 16 : section.getInt("a-offset", 16));
        int bOffset = Math.max(aOffset, section == null ? 80 : section.getInt("b-offset", 80));
        int backgroundLength = Math.max(1, section == null ? 4 : section.getInt("background-length", 4));
        int maxAttempts = Math.max(1, section == null ? 3 : section.getInt("max-attempts", 3));
        int moveInterval = Math.max(1, section == null ? 6 : section.getInt("move-interval", 6));
        int tumblerWidth = Math.max(1, section == null ? 3 : section.getInt("tumbler-width", 3));
        String renderMode = section == null ? "ascii" : section.getString("render-mode", "ascii");
        return new TumblerBarConfig(aStates, initialA, unlockA, bStates, bFinal,
                aOffset, bOffset, backgroundLength, maxAttempts, moveInterval, tumblerWidth, renderMode);
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    /** 机关 A 普通状态数。 */
    public int getAStates() {
        return aStates;
    }

    /** 尝试失败复位后 A 的初始状态。 */
    public int getInitialA() {
        return initialA;
    }

    /** 可解锁的 A 状态。 */
    public int getUnlockA() {
        return unlockA;
    }

    /** 机关 B 状态数。 */
    public int getBStates() {
        return bStates;
    }

    /** B 的最终状态（达到即成功）。 */
    public int getBFinal() {
        return bFinal;
    }

    /** A 左上角相对背景左端的水平像素位置。 */
    public int getAOffset() {
        return aOffset;
    }

    /** B 左上角相对背景左端的水平像素位置。 */
    public int getBOffset() {
        return bOffset;
    }

    /** 背景总长度（格）。 */
    public int getBackgroundLength() {
        return backgroundLength;
    }

    /** 最大尝试次数。 */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** A/D 调 A 的步进间隔（tick）。 */
    public int getMoveInterval() {
        return moveInterval;
    }

    /** 机关 A/B 状态图宽度（格）。 */
    public int getTumblerWidth() {
        return tumblerWidth;
    }

    /** 是否使用位图渲染（render-mode: bitmap）；否则使用 ASCII。 */
    public boolean isBitmap() {
        return "bitmap".equalsIgnoreCase(renderMode);
    }
}
