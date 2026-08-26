package com.lonleaf.chesttheft.config;

import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * 音效配置值对象：从配置段解析 key/source/volume/pitch；key 缺失或为空表示不播放。
 * 供各小游戏声音配置复用；key 支持命名空间路径（如 "minecraft:block.note_block.pling"）或 Bukkit 短名（如 "note.pling"）。
 */
public class SoundConfig {

    private final String key;
    private final SoundCategory category;
    private final float volume;
    private final float pitch;

    private SoundConfig(String key, SoundCategory category, float volume, float pitch) {
        this.key = key;
        this.category = category;
        this.volume = volume;
        this.pitch = pitch;
    }

    /** 从配置段解析音效（null 或缺失 key 表示不播放）。 */
    public static SoundConfig from(ConfigurationSection section) {
        if (section == null) {
            return new SoundConfig(null, SoundCategory.MASTER, 1.0f, 1.0f);
        }
        String key = section.getString("key");
        if (key == null || key.isEmpty()) {
            return new SoundConfig(null, SoundCategory.MASTER, 1.0f, 1.0f);
        }
        return new SoundConfig(key,
                parseCategory(section.getString("source")),
                (float) section.getDouble("volume", 1.0),
                (float) section.getDouble("pitch", 1.0));
    }

    /** 是否配置了可播放的音效。 */
    public boolean isEnabled() {
        return key != null && !key.isEmpty();
    }

    /** 向玩家播放音效（未配置时不播放）。 */
    public void play(Player player) {
        if (isEnabled()) {
            player.playSound(player.getLocation(), key, category, volume, pitch);
        }
    }

    private static SoundCategory parseCategory(String source) {
        if (source == null) {
            return SoundCategory.MASTER;
        }
        try {
            return SoundCategory.valueOf(source.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return SoundCategory.MASTER;
        }
    }
}
