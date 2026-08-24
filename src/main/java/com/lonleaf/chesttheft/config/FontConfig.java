package com.lonleaf.chesttheft.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 位图渲染字体配置（config.yml 的 font 小节）：偏移定位字体、游标位图字体与各自码位。
 * 码位须与服务器资源包（chesttheft:bar / chesttheft:offset_chars）一一对应，修改后需同步资源包。
 */
public class FontConfig {

    /** 偏移定位字体（space 类型）：把后续字符推进/回退指定像素。 */
    private final String offsetFont;
    /** 游标位图字体（bitmap 类型）：普通格 8px（底/未完成/游标/完成/空白）+ 边框格 9px（左右端）。 */
    private final String barFont;
    /** 像素偏移 → 码位字符（±1/2/4/8/16/32/64/128）。 */
    private final Map<Integer, Character> offsetChars;
    /** 位图底格字符（进度条背景）。 */
    private final char baseChar;
    /** 位图未完成格字符（判定点未命中）。 */
    private final char unhitChar;
    /** 位图游标格字符（当前游标）。 */
    private final char cursorChar;
    /** 位图完成格字符（判定点已命中）。 */
    private final char doneChar;
    /** 位图空白格字符（全透明，用于占位）。 */
    private final char blankChar;
    /** 位图左端边框字符（条起始装饰）。 */
    private final char leftBorderChar;
    /** 位图右端边框字符（条结束装饰）。 */
    private final char rightBorderChar;
    /** 机关 A 状态码位：状态值 → 字符（普通态）。 */
    private final Map<Integer, Character> aChars;
    /** 机关 A 状态码位：状态值 → 字符（特殊态，尝试失败时显示）。 */
    private final Map<Integer, Character> aSpecialChars;
    /** 机关 B 状态码位：状态值 → 字符。 */
    private final Map<Integer, Character> bChars;
    /** 机关转轮背景块字符列表（tumbler-bar 背景大图切块，每块 ≤256px 满足客户端字形尺寸限制，按序并列铺满整条）。 */
    private final List<Character> tumblerBgChars;
    // ==================== 位图计算参数（须与资源包一致，用于计算渲染偏移） ====================
    /** bar_cell.png 高度（像素）。 */
    private final int cellPngHeight;
    /** bar_cell 每字符切片宽度（像素）。 */
    private final int cellSliceWidth;
    /** bar.json 中 bar_cell provider 的 height（渲染高度，像素）。 */
    private final int cellHeight;
    /** bar_edge.png 高度（像素）。 */
    private final int edgePngHeight;
    /** bar_edge 每字符切片宽度（像素）。 */
    private final int edgeSliceWidth;
    /** bar.json 中 bar_edge provider 的 height（渲染高度，像素）。 */
    private final int edgeHeight;
    /** 客户端渲染中每个字形后固定的间隙（像素）。 */
    private final int glyphGap;
    /** 端点游标重叠时，边框格外侧露出的边框像素（如边框格 15px、游标 8px 时设 3，左右各露出 3px）。 */
    private final int edgeOverlayOutset;
    /** 机关转轮（tumbler-bar）背景格/A/B 图标尺寸（像素，48x48 方块；须与资源包 tumbler_bg/tumbler_a/tumbler_b 一致）。 */
    private final int tumblerTile;
    /** 机关转轮 A/B/背景 PNG 高度（像素，须与资源包 PNG 一致）。 */
    private final int tumblerPngHeight;
    /** bar.json 中 tumbler provider 的 height（渲染高度，像素）。 */
    private final int tumblerHeight;
    /** bar.json 中 tumbler provider 的 ascent（基线以上像素，须 ≤ tumblerHeight）。 */
    private final int tumblerAscent;
    /** 位图条整体水平平移（像素，正=右移）：纯服务端渲染偏移，/reload 即生效，无需材质包。
     *  垂直方向无法由服务端调整：字幕渲染基线由客户端引擎硬编码，像素级垂直偏移只能改材质包 bar.json 的 ascent。 */
    private final int horizontalOffset;

    private FontConfig(String offsetFont, String barFont, Map<Integer, Character> offsetChars,
                       char baseChar, char unhitChar, char cursorChar, char doneChar, char blankChar,
                       char leftBorderChar, char rightBorderChar,
                       Map<Integer, Character> aChars, Map<Integer, Character> aSpecialChars,
                       Map<Integer, Character> bChars, List<Character> tumblerBgChars,
                       int cellPngHeight, int cellSliceWidth, int cellHeight,
                       int edgePngHeight, int edgeSliceWidth, int edgeHeight, int glyphGap,
                       int edgeOverlayOutset, int tumblerTile,
                       int tumblerPngHeight, int tumblerHeight, int tumblerAscent,
                       int horizontalOffset) {
        this.offsetFont = offsetFont;
        this.barFont = barFont;
        this.offsetChars = Collections.unmodifiableMap(offsetChars);
        this.baseChar = baseChar;
        this.unhitChar = unhitChar;
        this.cursorChar = cursorChar;
        this.doneChar = doneChar;
        this.blankChar = blankChar;
        this.leftBorderChar = leftBorderChar;
        this.rightBorderChar = rightBorderChar;
        this.aChars = Collections.unmodifiableMap(aChars);
        this.aSpecialChars = Collections.unmodifiableMap(aSpecialChars);
        this.bChars = Collections.unmodifiableMap(bChars);
        this.tumblerBgChars = Collections.unmodifiableList(new ArrayList<>(tumblerBgChars));
        this.cellPngHeight = cellPngHeight;
        this.cellSliceWidth = cellSliceWidth;
        this.cellHeight = cellHeight;
        this.edgePngHeight = edgePngHeight;
        this.edgeSliceWidth = edgeSliceWidth;
        this.edgeHeight = edgeHeight;
        this.glyphGap = glyphGap;
        this.edgeOverlayOutset = edgeOverlayOutset;
        this.tumblerTile = tumblerTile;
        this.tumblerPngHeight = tumblerPngHeight;
        this.tumblerHeight = tumblerHeight;
        this.tumblerAscent = tumblerAscent;
        this.horizontalOffset = horizontalOffset;
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
        char unhit = parseChar(readHex(section, "bar-chars.unhit", 0xE101));
        char cursor = parseChar(readHex(section, "bar-chars.cursor", 0xE102));
        char done = parseChar(readHex(section, "bar-chars.done", 0xE106));
        char blank = parseChar(readHex(section, "bar-chars.blank", 0xE103));
        char left = parseChar(readHex(section, "bar-chars.left", 0xE104));
        char right = parseChar(readHex(section, "bar-chars.right", 0xE105));

        // 机关 A/B 状态码位（tumbler-bar 玩法）：A 普通态 4 个 + 特殊态 4 个 + B 状态 5 个
        Map<Integer, Character> aChars = parseStateChars(section, "tumbler-chars.a",
                new int[]{0xE120, 0xE121, 0xE122, 0xE123});
        Map<Integer, Character> aSpecialChars = parseStateChars(section, "tumbler-chars.a-special",
                new int[]{0xE124, 0xE125, 0xE126, 0xE127});
        Map<Integer, Character> bChars = parseStateChars(section, "tumbler-chars.b",
                new int[]{0xE130, 0xE131, 0xE132, 0xE133, 0xE134});
        // 机关转轮背景块：背景整图单字符（128x128 ≤ 客户端 256 限制，无拼接缝隙）
        List<Character> tumblerBg = parseChars(section, "tumbler-chars.background",
                new int[]{0xE140});

        // 位图计算参数（font.bitmap 小节），缺失键使用默认值（与仓库测试资源包一致）
        ConfigurationSection bitmap = section == null ? null : section.getConfigurationSection("bitmap");
        int cellPngHeight = readInt(bitmap, "cell-png-height", 8);
        int cellSliceWidth = readInt(bitmap, "cell-slice-width", 8);
        int cellHeight = readInt(bitmap, "cell-height", 8);
        int edgePngHeight = readInt(bitmap, "edge-png-height", 8);
        int edgeSliceWidth = readInt(bitmap, "edge-slice-width", 9);
        int edgeHeight = readInt(bitmap, "edge-height", 8);
        int glyphGap = readInt(bitmap, "glyph-gap", 1);
        int edgeOverlayOutset = readInt(bitmap, "edge-overlay-outset", 1);
        int tumblerTile = readInt(bitmap, "tumbler-tile", 48);
        int tumblerPngHeight = readInt(bitmap, "tumbler-png-height", 96);
        int tumblerHeight = readInt(bitmap, "tumbler-height", 96);
        int tumblerAscent = readInt(bitmap, "tumbler-ascent", 48);
        int horizontalOffset = readInt(bitmap, "horizontal-offset", 0);
        return new FontConfig(offsetFont, barFont, offsetChars, base, unhit, cursor, done, blank, left, right,
                aChars, aSpecialChars, bChars, tumblerBg,
                cellPngHeight, cellSliceWidth, cellHeight, edgePngHeight, edgeSliceWidth, edgeHeight, glyphGap,
                edgeOverlayOutset, tumblerTile, tumblerPngHeight, tumblerHeight, tumblerAscent,
                horizontalOffset);
    }

    /**
     * 解析状态值 → 码位映射（如 tumbler-chars.a）：状态数 = 配置最大数字键 + 1，未配置回退默认序列。
     */
    private static Map<Integer, Character> parseStateChars(ConfigurationSection section, String key, int[] defaults) {
        ConfigurationSection sub = section == null ? null : section.getConfigurationSection(key);
        Map<Integer, Character> map = new HashMap<>();
        int count = defaults.length;
        if (sub != null) {
            for (String k : sub.getKeys(false)) {
                try {
                    count = Math.max(count, Integer.parseInt(k) + 1);
                } catch (NumberFormatException ignored) {
                    // 忽略非数字键
                }
            }
        }
        for (int i = 0; i < count; i++) {
            // 默认序列之外的状态码位按最后一个默认码位顺延，仅作兜底（正常由配置显式指定）
            int fallback = i < defaults.length ? defaults[i] : defaults[defaults.length - 1] + (i - defaults.length + 1);
            map.put(i, parseChar(readHex(sub, String.valueOf(i), fallback)));
        }
        return map;
    }

    /** 读取整数配置，缺失或非法时使用默认值。 */
    private static int readInt(ConfigurationSection section, String key, int fallback) {
        if (section == null) {
            return fallback;
        }
        String raw = section.getString(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    /** 解析码位字符列表配置（逗号分隔十六进制，不带 \\u 前缀）：缺失或为空时使用默认码位。 */
    private static List<Character> parseChars(ConfigurationSection section, String key, int[] defaults) {
        List<Character> list = new ArrayList<>();
        String raw = section == null ? null : section.getString(key);
        String[] parts = (raw == null || raw.isBlank())
                ? new String[0]
                : raw.trim().split("\\s*,\\s*");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                list.add((char) Integer.parseInt(part, 16));
            } catch (NumberFormatException e) {
                // 忽略非法码位项
            }
        }
        if (list.isEmpty()) {
            for (int d : defaults) {
                list.add((char) d);
            }
        }
        return list;
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

    /** 位图未完成格字符（判定点未命中）。 */
    public char getUnhitChar() {
        return unhitChar;
    }

    /** 位图游标格字符（当前游标）。 */
    public char getCursorChar() {
        return cursorChar;
    }

    /** 位图完成格字符（判定点已命中）。 */
    public char getDoneChar() {
        return doneChar;
    }

    /** 位图空白格字符（全透明，用于占位）。 */
    public char getBlankChar() {
        return blankChar;
    }

    /** 位图左端边框字符（条起始装饰）。 */
    public char getLeftBorderChar() {
        return leftBorderChar;
    }

    /** 位图右端边框字符（条结束装饰）。 */
    public char getRightBorderChar() {
        return rightBorderChar;
    }

    /** 机关 A 普通态码位：状态值不在范围内时返回空格（不渲染）。 */
    public char getAChar(int state) {
        return aChars.getOrDefault(state, ' ');
    }

    /** 机关 A 特殊态码位（尝试失败时显示）：状态值不在范围内时返回空格。 */
    public char getASpecialChar(int state) {
        return aSpecialChars.getOrDefault(state, ' ');
    }

    /** 机关 B 状态码位：状态值不在范围内时返回空格。 */
    public char getBChar(int state) {
        return bChars.getOrDefault(state, ' ');
    }

    /** 机关转轮背景块字符列表（按序并列铺满整条；块数须能整除背景总宽）。 */
    public List<Character> getTumblerBgChars() {
        return tumblerBgChars;
    }

    /** bar_cell.png 高度（像素）。 */
    public int getCellPngHeight() {
        return cellPngHeight;
    }

    /** bar_cell 每字符切片宽度（像素）。 */
    public int getCellSliceWidth() {
        return cellSliceWidth;
    }

    /** bar.json 中 bar_cell provider 的 height（渲染高度，像素）。 */
    public int getCellHeight() {
        return cellHeight;
    }

    /** bar_edge.png 高度（像素）。 */
    public int getEdgePngHeight() {
        return edgePngHeight;
    }

    /** bar_edge 每字符切片宽度（像素）。 */
    public int getEdgeSliceWidth() {
        return edgeSliceWidth;
    }

    /** bar.json 中 bar_edge provider 的 height（渲染高度，像素）。 */
    public int getEdgeHeight() {
        return edgeHeight;
    }

    /** 客户端渲染中每个字形后固定的间隙（像素）。 */
    public int getGlyphGap() {
        return glyphGap;
    }

    /** 端点游标重叠时，边框格外侧露出的边框像素。 */
    public int getEdgeOverlayOutset() {
        return edgeOverlayOutset;
    }

    /** 机关转轮（tumbler-bar）背景格/A/B 图标尺寸（像素）。 */
    public int getTumblerTile() {
        return tumblerTile;
    }

    /** 机关转轮 A/B/背景 PNG 高度（像素）。 */
    public int getTumblerPngHeight() {
        return tumblerPngHeight;
    }

    /** bar.json 中 tumbler provider 的 height（渲染高度，像素）。 */
    public int getTumblerHeight() {
        return tumblerHeight;
    }

    /** bar.json 中 tumbler provider 的 ascent（基线以上像素）。 */
    public int getTumblerAscent() {
        return tumblerAscent;
    }

    /** 位图条整体水平平移（像素，正=右移；纯服务端偏移，reload 即生效）。 */
    public int getHorizontalOffset() {
        return horizontalOffset;
    }
}
