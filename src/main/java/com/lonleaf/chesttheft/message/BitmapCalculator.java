package com.lonleaf.chesttheft.message;

import com.lonleaf.chesttheft.config.FontConfig;

/**
 * 位图渲染偏移计算：基于资源包参数计算每字形后应插入的间隙补偿偏移，使位图格子无缝贴合。
 * 补偿公式：advance(上取整) + glyph-gap - 渲染宽(下取整)，详见 docs/代码详细注释.md 模块E。
 */
public final class BitmapCalculator {

    private static FontConfig font;

    private BitmapCalculator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** 初始化字体配置（插件启动时调用）；未初始化时返回 0。 */
    public static void init(FontConfig fontConfig) {
        font = fontConfig;
    }

    /** 普通格（底格/未完成格/游标格）每字形后的间隙补偿（负偏移像素数）。 */
    public static int cellGap() {
        return font == null ? 0
                : compensation(font.getCellSliceWidth(), font.getCellPngHeight(), font.getCellHeight(), font.getGlyphGap());
    }

    /** 边框格（左/右端）每字形后的间隙补偿（负偏移像素数）。 */
    public static int edgeGap() {
        return font == null ? 0
                : compensation(font.getEdgeSliceWidth(), font.getEdgePngHeight(), font.getEdgeHeight(), font.getGlyphGap());
    }

    // ==================== 端点游标重叠（游标覆盖边框格主体、露出外侧边框像素） ====================
    // 渲染几何见 docs/代码详细注释.md 模块E；leftOverlayBack/rightOverlayBack 计算回退量，
    // leftOverlayTail 计算游标后推进量使下一格起点对齐。

    /** 字形间隙（像素）。 */
    public static int glyphGap() {
        return font == null ? 1 : font.getGlyphGap();
    }

    /** 边框格缩放后 advance（排版推进，上取整）。 */
    public static int edgeAdvance() {
        return font == null ? 0 : (int) Math.ceil(font.getEdgeSliceWidth() * scale(font.getEdgePngHeight(), font.getEdgeHeight()));
    }

    /** 边框格缩放后渲染宽（下取整）。 */
    public static int edgeRenderWidth() {
        return font == null ? 0 : (int) Math.floor(font.getEdgeSliceWidth() * scale(font.getEdgePngHeight(), font.getEdgeHeight()));
    }

    /** 普通格缩放后 advance（排版推进，上取整）。 */
    public static int cellAdvance() {
        return font == null ? 0 : (int) Math.ceil(font.getCellSliceWidth() * scale(font.getCellPngHeight(), font.getCellHeight()));
    }

    /** 机关转轮（tumbler-bar）背景格/A/B 图标尺寸（像素，默认 48x48 方块；须与资源包一致）。 */
    public static int tumblerTile() {
        return font == null ? 48 : font.getTumblerTile();
    }

    /** 机关转轮 A/B/背景 PNG 高度（像素，默认 96；须与资源包一致）。 */
    public static int tumblerPngHeight() {
        return font == null ? 96 : font.getTumblerPngHeight();
    }

    /** bar.json 中 tumbler provider 的 height（渲染高度，像素，默认 96）。 */
    public static int tumblerHeight() {
        return font == null ? 96 : font.getTumblerHeight();
    }

    /** bar.json 中 tumbler provider 的 ascent（基线以上像素，默认 48）。 */
    public static int tumblerAscent() {
        return font == null ? 48 : font.getTumblerAscent();
    }

    /** 位图条整体水平平移（像素，正=右移；纯服务端偏移，/reload 即生效，无需材质包）。 */
    public static int horizontalOffset() {
        return font == null ? 0 : font.getHorizontalOffset();
    }

    /**
     * 左端游标重叠：边框格后回退量（像素），使游标起点落在边框格左侧 outset px 处，
     * 露出外侧 outset px 边框像素。
     */
    public static int leftOverlayBack() {
        return edgeAdvance() + glyphGap() - outset();
    }

    /**
     * 左端游标重叠：游标后回退量（像素，可为负表示向前推进），使下一格起点与边框格渲染终点对齐
     * （无缝且总宽不变）。
     */
    public static int leftOverlayTail() {
        return outset() + cellAdvance() + glyphGap() - edgeRenderWidth();
    }

    /**
     * 右端游标重叠：边框格后回退量（像素），使游标覆盖边框格主体、露出外侧 outset px 边框像素。
     */
    public static int rightOverlayBack() {
        return edgeAdvance() + glyphGap() - edgeRenderWidth() + cellRenderWidth() + outset();
    }

    /** 普通格缩放后渲染宽（下取整）。 */
    public static int cellRenderWidth() {
        return font == null ? 0 : (int) Math.floor(font.getCellSliceWidth() * scale(font.getCellPngHeight(), font.getCellHeight()));
    }

    /** 端点游标重叠时外侧露出的边框像素（edge-overlay-outset）。 */
    private static int outset() {
        return font == null ? 1 : font.getEdgeOverlayOutset();
    }

    private static double scale(int pngHeight, int height) {
        return height / (double) pngHeight;
    }

    /**
     * 计算单字形间隙补偿量（像素）。
     *
     * @param sliceWidth 切片宽度（像素）
     * @param pngHeight  PNG 高度（像素）
     * @param height     provider 的 height（渲染高度，像素）
     * @param glyphGap   引擎每字形后固定间隙（像素）
     * @return 补偿量：使下一字形起点与当前字形渲染终点对齐
     */
    private static int compensation(int sliceWidth, int pngHeight, int height, int glyphGap) {
        double scale = height / (double) pngHeight;
        int advance = (int) Math.ceil(sliceWidth * scale);
        int renderWidth = (int) Math.floor(sliceWidth * scale);
        return advance + glyphGap - renderWidth;
    }
}
