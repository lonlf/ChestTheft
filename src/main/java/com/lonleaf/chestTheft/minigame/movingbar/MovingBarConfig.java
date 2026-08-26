package com.lonleaf.chesttheft.minigame.movingbar;

import com.lonleaf.chesttheft.config.SoundConfig;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 移动游标小游戏（moving-bar）特有配置：进度条长度、未完成判定区长度、光标移动间隔、渲染方式。
 */
public class MovingBarConfig {

    private final int barLength;
    private final int unhitLength;
    private final int moveInterval;
    /** 游标是否自动往复移动；false 时改为骑乘模式，按 A/D 键控制游标左右移动。 */
    private final boolean oscillate;
    /** 渲染方式：ascii（字符条，默认，无需材质包）/ bitmap（位图，需安装材质包）。 */
    private final String renderMode;
    /** 游标移动音效（每格一声）。 */
    private final SoundConfig moveSound;
    /** 点击命中判定区音效。 */
    private final SoundConfig hitSound;

    private MovingBarConfig(int barLength, int unhitLength, int moveInterval, boolean oscillate, String renderMode,
                            SoundConfig moveSound, SoundConfig hitSound) {
        this.barLength = barLength;
        this.unhitLength = unhitLength;
        this.moveInterval = moveInterval;
        this.oscillate = oscillate;
        this.renderMode = renderMode;
        this.moveSound = moveSound;
        this.hitSound = hitSound;
    }

    /** 从配置段解析玩法特有参数，缺失键使用默认值并做范围约束（null 表示全用默认值）。
     *  等级配置通常已由 LockConfigManager 与默认配置合并，缺失键即继承默认配置的值。 */
    public static MovingBarConfig from(ConfigurationSection section) {
        int barLength = Math.max(5, section == null ? 15 : section.getInt("bar-length", 15));
        int unhitLength = Math.max(1, Math.min(barLength - 2,
                section == null ? 3 : section.getInt("unhit-length", 3)));
        int moveInterval = Math.max(1, section == null ? 10 : section.getInt("move-interval", 10));
        boolean oscillate = section == null || section.getBoolean("oscillate", true);
        String renderMode = section == null ? "ascii" : section.getString("render-mode", "ascii");
        ConfigurationSection sounds = section == null ? null : section.getConfigurationSection("sounds");
        SoundConfig moveSound = SoundConfig.from(sounds == null ? null : sounds.getConfigurationSection("move"));
        SoundConfig hitSound = SoundConfig.from(sounds == null ? null : sounds.getConfigurationSection("hit"));
        return new MovingBarConfig(barLength, unhitLength, moveInterval, oscillate, renderMode, moveSound, hitSound);
    }

    /** 进度条总格数（含两端边框）。 */
    public int getBarLength() {
        return barLength;
    }

    /** 未完成判定区（游标需进入后点击判定）长度。 */
    public int getUnhitLength() {
        return unhitLength;
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

    /** 游标移动音效（每格一声）。 */
    public SoundConfig getMoveSound() {
        return moveSound;
    }

    /** 点击命中判定区音效。 */
    public SoundConfig getHitSound() {
        return hitSound;
    }
}
