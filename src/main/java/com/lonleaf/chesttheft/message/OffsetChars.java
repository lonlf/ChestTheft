package com.lonleaf.chesttheft.message;

import com.lonleaf.chesttheft.config.FontConfig;

import java.util.List;

/**
 * 偏移字符工具：把像素偏移量转换为资源包 space 字体字符组合（二进制贪心），用于位图定位。
 */
public final class OffsetChars {

    /** 2 的幂偏移量（像素），从大到小贪心组合出最短字符序列。 */
    private static final int[] POWERS = {128, 64, 32, 16, 8, 4, 2, 1};

    private static FontConfig font;

    private OffsetChars() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** 初始化字体配置（插件启动时调用）；未初始化时偏移返回空串。 */
    public static void init(FontConfig fontConfig) {
        font = fontConfig;
    }

    /**
     * 生成偏移字符序列（不带字体标签）：正数向右推进（移动后续内容），负数向左回退。
     *
     * @param pixels 像素偏移量
     * @return 偏移字符序列，偏移为 0 或未初始化时返回空串
     */
    public static String raw(int pixels) {
        if (font == null || pixels == 0) {
            return "";
        }
        int abs = Math.abs(pixels);
        StringBuilder sb = new StringBuilder();
        if (pixels > 0) {
            append(sb, abs, true);
        } else {
            append(sb, abs, false);
        }
        return sb.toString();
    }

    /** 把 n 按 2 的幂拆分追加到缓冲区，positive 决定取正/负偏移字符。 */
    private static void append(StringBuilder sb, int n, boolean positive) {
        for (int power : POWERS) {
            while (n >= power) {
                Character ch = font.getOffsetChars().get(positive ? power : -power);
                if (ch != null) {
                    sb.append(ch.charValue());
                }
                n -= power;
            }
        }
    }

    // ==================== 字体访问（渲染游标条用） ====================

    /** 偏移定位字体名（如 chesttheft:offset_chars），未初始化时为 null。 */
    public static String offsetFont() {
        return font == null ? null : font.getOffsetFont();
    }

    /** 位图字体名（如 chesttheft:bar），未初始化时为 null。 */
    public static String barFont() {
        return font == null ? null : font.getBarFont();
    }

    /** 位图底格字符。 */
    public static char baseChar() {
        return font == null ? ' ' : font.getBaseChar();
    }

    /** 位图未完成格字符（判定点未命中）。 */
    public static char unhitChar() {
        return font == null ? ' ' : font.getUnhitChar();
    }

    /** 位图游标格字符（当前游标）。 */
    public static char cursorChar() {
        return font == null ? ' ' : font.getCursorChar();
    }

    /** 位图完成格字符（判定点已命中）。 */
    public static char doneChar() {
        return font == null ? ' ' : font.getDoneChar();
    }

    /** 位图左端边框字符。 */
    public static char leftBorderChar() {
        return font == null ? ' ' : font.getLeftBorderChar();
    }

    /** 位图右端边框字符。 */
    public static char rightBorderChar() {
        return font == null ? ' ' : font.getRightBorderChar();
    }

    /** 机关 A 普通态码位（tumbler-bar 玩法）。 */
    public static char aChar(int state) {
        return font == null ? ' ' : font.getAChar(state);
    }

    /** 机关 A 特殊态码位（尝试失败时显示）。 */
    public static char aSpecialChar(int state) {
        return font == null ? ' ' : font.getASpecialChar(state);
    }

    /** 机关 B 状态码位。 */
    public static char bChar(int state) {
        return font == null ? ' ' : font.getBChar(state);
    }

    /** 机关转轮背景块字符列表（tumbler-bar 背景大图切块，按序并列铺满整条）。 */
    public static List<Character> tumblerBgChars() {
        return font == null ? List.of(' ') : font.getTumblerBgChars();
    }
}
