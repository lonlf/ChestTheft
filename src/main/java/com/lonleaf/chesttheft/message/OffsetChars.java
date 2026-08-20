package com.lonleaf.chesttheft.message;

import com.lonleaf.chesttheft.config.FontConfig;

/**
 * 偏移字符工具：把像素偏移量转换为资源包 space 字体中的最短字符组合
 * （1/2/4/8/16/32/64/128 二进制约减），用于在位图渲染中精确定位游标/判定区。
 * 与字体名分离返回（{@link #raw(int)} + {@link #offsetFont()}），
 * 由 ChatJson 组装为带 font 字段的 ChatComponent JSON 段。
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

    /** 位图红格字符。 */
    public static char redChar() {
        return font == null ? ' ' : font.getRedChar();
    }

    /** 位图指针格字符。 */
    public static char pointerChar() {
        return font == null ? ' ' : font.getPointerChar();
    }
}
