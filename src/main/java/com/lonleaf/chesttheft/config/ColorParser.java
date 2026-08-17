package com.lonleaf.chesttheft.config;

import org.bukkit.Color;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 颜色名解析：支持常见颜色名 #RRGGBB 十六进制。
 * 仅依赖 org.bukkit.Color 常量
 */
public final class ColorParser {

    private static final Map<String, Color> COLORS = new HashMap<>();

    static {
        COLORS.put("WHITE", Color.WHITE);
        COLORS.put("SILVER", Color.SILVER);
        COLORS.put("GRAY", Color.GRAY);
        COLORS.put("BLACK", Color.BLACK);
        COLORS.put("RED", Color.RED);
        COLORS.put("MAROON", Color.MAROON);
        COLORS.put("YELLOW", Color.YELLOW);
        COLORS.put("OLIVE", Color.OLIVE);
        COLORS.put("LIME", Color.LIME);
        COLORS.put("GREEN", Color.GREEN);
        COLORS.put("TEAL", Color.TEAL);
        COLORS.put("AQUA", Color.AQUA);
        COLORS.put("BLUE", Color.BLUE);
        COLORS.put("NAVY", Color.NAVY);
        COLORS.put("FUCHSIA", Color.FUCHSIA);
        COLORS.put("PURPLE", Color.PURPLE);
        COLORS.put("ORANGE", Color.ORANGE);
        COLORS.put("GOLD", Color.fromRGB(255, 215, 0));
    }

    private ColorParser() {
    }

    /** 解析颜色：支持颜色名或 #RRGGBB 十六进制；非法返回 null。 */
    public static Color parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            try {
                return Color.fromRGB(Integer.parseInt(trimmed.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return COLORS.get(trimmed.toUpperCase(Locale.ROOT));
    }
}
