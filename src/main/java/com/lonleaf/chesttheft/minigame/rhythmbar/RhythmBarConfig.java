package com.lonleaf.chesttheft.minigame.rhythmbar;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 节奏条小游戏（rhythm-bar）特有配置：进度条长度、未完成判定点数量/长度/最小间隔、光标移动间隔、渲染方式。
 */
public class RhythmBarConfig {

    private final int barLength;
    private final int hitCount;
    private final int hitLength;
    private final int hitMinGap;
    private final int moveInterval;
    /** 游标是否自动往复移动；false 时改为骑乘模式，按 A/D 键控制游标左右移动。 */
    private final boolean oscillate;
    /** 渲染方式：ascii（字符条，默认，无需材质包）/ bitmap（位图，需安装材质包）。 */
    private final String renderMode;

    private RhythmBarConfig(int barLength, int hitCount, int hitLength, int hitMinGap,
                            int moveInterval, boolean oscillate, String renderMode) {
        this.barLength = barLength;
        this.hitCount = hitCount;
        this.hitLength = hitLength;
        this.hitMinGap = hitMinGap;
        this.moveInterval = moveInterval;
        this.oscillate = oscillate;
        this.renderMode = renderMode;
    }

    /** 从配置段解析玩法特有参数，缺失键使用默认值并做范围约束（null 表示全用默认值）。
     *  等级配置通常已由 LockConfigManager 与默认配置合并，缺失键即继承默认配置的值。 */
    public static RhythmBarConfig from(ConfigurationSection section) {
        int barLength = Math.max(5, section == null ? 20 : section.getInt("bar-length", 20));
        int hitLength = Math.max(1, Math.min(barLength / 2,
                section == null ? 2 : section.getInt("hit-length", 2)));
        int hitMinGap = Math.max(0, section == null ? 3 : section.getInt("hit-min-gap", 3));
        // 判定点数量受条长约束：最多容纳 (barLength-2+gap)/(len+gap) 个
        int hitCount = Math.max(1, section == null ? 3 : section.getInt("hit-count", 3));
        int maxFit = Math.max(1, (barLength - 2 + hitMinGap) / (hitLength + hitMinGap));
        hitCount = Math.min(hitCount, maxFit);
        int moveInterval = Math.max(1, section == null ? 8 : section.getInt("move-interval", 8));
        boolean oscillate = section == null || section.getBoolean("oscillate", true);
        String renderMode = section == null ? "ascii" : section.getString("render-mode", "ascii");
        return new RhythmBarConfig(barLength, hitCount, hitLength, hitMinGap, moveInterval, oscillate, renderMode);
    }

    /** 进度条总格数（含两端边框）。 */
    public int getBarLength() {
        return barLength;
    }

    /** 未完成判定点数量：需依次全部命中。 */
    public int getHitCount() {
        return hitCount;
    }

    /** 每个判定点长度（格数）。 */
    public int getHitLength() {
        return hitLength;
    }

    /** 判定点之间的最小间隔（格数）。 */
    public int getHitMinGap() {
        return hitMinGap;
    }

    /** 光标移动间隔（tick，20 tick = 1 秒）。 */
    public int getMoveInterval() {
        return moveInterval;
    }

    /** 游标是否自动往复移动；false 时改为骑乘模式，按 A/D 键控制游标左右移动。 */
    public boolean isOscillate() {
        return oscillate;
    }

    /** 是否使用位图渲染（render-mode: bitmap，需玩家安装材质包）；否则使用 ASCII 字符条。 */
    public boolean isBitmap() {
        return "bitmap".equalsIgnoreCase(renderMode);
    }
}
