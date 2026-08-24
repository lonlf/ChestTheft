package com.lonleaf.chesttheft.message;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatComponent JSON 构建器：合并多个文本段（可分别指定字体），用于位图渲染。
 */
public final class ChatJson {

    private final List<String> segments = new ArrayList<>();
    /** 阴影颜色（ARGB 整型；0 表示阴影完全透明；null 表示继承默认渲染）。 */
    private Integer shadowColor;

    private ChatJson() {
    }

    /** 新建空构建器。 */
    public static ChatJson create() {
        return new ChatJson();
    }

    /** 关闭文本阴影（如位图条，避免字形下方出现 1px 暗色副本）。1.21.4+ 用 shadow_color 控制。 */
    public ChatJson noShadow() {
        this.shadowColor = 0;
        return this;
    }

    /** 追加纯文本段（默认字体）。 */
    public ChatJson text(String text) {
        if (text != null && !text.isEmpty()) {
            segments.add("{\"text\":\"" + escape(text) + "\"}");
        }
        return this;
    }

    /** 追加指定字体的文本段（如 {"text":"...","font":"chesttheft:bar"}）。 */
    public ChatJson text(String text, String font) {
        if (text == null || text.isEmpty()) {
            return this;
        }
        StringBuilder sb = new StringBuilder("{\"text\":\"").append(escape(text)).append('"');
        if (font != null && !font.isEmpty()) {
            sb.append(",\"font\":\"").append(font).append('"');
        }
        sb.append('}');
        segments.add(sb.toString());
        return this;
    }

    /** 合并为 ChatComponent JSON：单段直接输出，多段用 extra 数组；shadow_color 选项注入根组件。 */
    public String build() {
        if (segments.isEmpty()) {
            return applyShadow("{\"text\":\"\"}");
        }
        if (segments.size() == 1) {
            return applyShadow(segments.get(0));
        }
        return applyShadow("{\"text\":\"\",\"extra\":[" + String.join(",", segments) + "]}");
    }

    /** 在组件末尾注入 shadow_color 属性（若设置了）。 */
    private String applyShadow(String json) {
        if (shadowColor == null) {
            return json;
        }
        return json.substring(0, json.length() - 1) + ",\"shadow_color\":" + shadowColor + "}";
    }

    /** JSON 字符串转义：处理引号、反斜杠与控制字符（含非 BMP 代理对按 \\u 转义）。 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
