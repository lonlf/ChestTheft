package com.lonleaf.chesttheft.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 位图渲染字体配置（config.yml 的 font 小节）：偏移定位字体、游标位图字体与各自码位。
 * 码位与生成的资源包（ResourcePackManager）一一对应，修改后需重新生成资源包。
 */
public class FontConfig {

    /** 偏移定位字体（space 类型）：把后续字符推进/回退指定像素。 */
    private final String offsetFont;
    /** 游标位图字体（bitmap 类型）：底格/红格/指针格/空白格。 */
    private final String barFont;
    /** 像素偏移 → 码位字符（±1/2/4/8/16/32/64/128）。 */
    private final Map<Integer, Character> offsetChars;
    /** 位图底格字符（进度条背景）。 */
    private final char baseChar;
    /** 位图红格字符（成功判定区）。 */
    private final char redChar;
    /** 位图指针格字符（当前游标）。 */
    private final char pointerChar;
    /** 位图空白格字符（全透明，用于占位）。 */
    private final char blankChar;

    private FontConfig(String offsetFont, String barFont, Map<Integer, Character> offsetChars,
                       char baseChar, char redChar, char pointerChar, char blankChar) {
        this.offsetFont = offsetFont;
        this.barFont = barFont;
        this.offsetChars = Collections.unmodifiableMap(offsetChars);
        this.baseChar = baseChar;
        this.redChar = redChar;
        this.pointerChar = pointerChar;
        this.blankChar = blankChar;
    }

    /** 从配置段解析字体配置，缺失键使用默认值（null 表示全用默认值）。 */
    public static FontConfig from(ConfigurationSection section) {
        String offsetFont = section == null ? "chesttheft:offset_chars"
                : section.getString("offset", "chesttheft:offset_chars");
        String barFont = section == null ? "chesttheft:bar"
                : section.getString("bar", "chesttheft:bar");

        Map<Integer, Character> offsetChars = new HashMap<>();
        // 默认码位：负偏移 e001-e008，正偏移 e00a-e011（与资源包 offset_chars.json 一致）
        int[] neg = {1, 2, 4, 8, 16, 32, 64, 128};
        int[] negCp = {0xE001, 0xE002, 0xE003, 0xE004, 0xE005, 0xE006, 0xE007, 0xE008};
        int[] posCp = {0xE00A, 0xE00B, 0xE00C, 0xE00D, 0xE00E, 0xE00F, 0xE010, 0xE011};
        for (int i = 0; i < neg.length; i++) {
            offsetChars.put(-neg[i], parseChar(readHex(section, "offset-chars.-" + neg[i], negCp[i])));
            offsetChars.put(neg[i], parseChar(readHex(section, "offset-chars." + neg[i], posCp[i])));
        }

        char base = parseChar(readHex(section, "bar-chars.base", 0xE100));
        char red = parseChar(readHex(section, "bar-chars.red", 0xE101));
        char pointer = parseChar(readHex(section, "bar-chars.pointer", 0xE102));
        char blank = parseChar(readHex(section, "bar-chars.blank", 0xE103));
        return new FontConfig(offsetFont, barFont, offsetChars, base, red, pointer, blank);
    }

    /** 读取十六进制码位配置（不带 \\u 前缀），缺失或非法时使用默认值。 */
    private static String readHex(ConfigurationSection section, String key, int fallback) {
        if (section == null) {
            return Integer.toHexString(fallback);
        }
        String raw = section.getString(key);
        if (raw == null) {
            return Integer.toHexString(fallback);
        }
        try {
            Integer.parseInt(raw.trim(), 16);
            return raw.trim();
        } catch (NumberFormatException e) {
            return Integer.toHexString(fallback);
        }
    }

    /** 十六进制字符串 → 字符。 */
    private static char parseChar(String hex) {
        return (char) Integer.parseInt(hex, 16);
    }

    /** 偏移定位字体（space 类型），如 chesttheft:offset_chars。 */
    public String getOffsetFont() {
        return offsetFont;
    }

    /** 游标位图字体（bitmap 类型），如 chesttheft:bar。 */
    public String getBarFont() {
        return barFont;
    }

    /** 像素偏移 → 码位字符映射（±1/2/4/8/16/32/64/128）。 */
    public Map<Integer, Character> getOffsetChars() {
        return offsetChars;
    }

    /** 位图底格字符（进度条背景）。 */
    public char getBaseChar() {
        return baseChar;
    }

    /** 位图红格字符（成功判定区）。 */
    public char getRedChar() {
        return redChar;
    }

    /** 位图指针格字符（当前游标）。 */
    public char getPointerChar() {
        return pointerChar;
    }

    /** 位图空白格字符（全透明，用于占位）。 */
    public char getBlankChar() {
        return blankChar;
    }
}
