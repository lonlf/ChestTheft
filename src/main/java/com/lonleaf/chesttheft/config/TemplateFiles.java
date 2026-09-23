package com.lonleaf.chesttheft.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 配置模板（jar 内 templates/&lt;语言&gt;/xxx.yml）的按语言读写：
 * 首次生成 config.yml / gamelevel/locklevel.yml / lootchest/lootchest.yml 时按当前语言选择带对应语言注释的模板；
 * 注释语言与运行语言不联动——切换语言注释需删除对应文件后重新生成。
 */
public final class TemplateFiles {

    /** 内置模板语言目录（与 lang/ 语言文件使用同一语言码）。 */
    private static final String[] TEMPLATE_LANGS = {"zh_cn", "en_gb"};

    private TemplateFiles() {
    }

    /** 语言码 → 模板目录：中文（zh 前缀）用 zh_cn，其余用 en_gb。 */
    public static String templateLang(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("zh") ? "zh_cn" : "en_gb";
    }

    /** 打开 jar 内指定语言模板的输入流；该语言模板缺失时按 en_gb → zh_cn 兜底，全部缺失返回 null。 */
    public static InputStream openTemplate(JavaPlugin plugin, String language, String fileName) {
        String lang = templateLang(language);
        InputStream fallback = null;
        for (String dir : TEMPLATE_LANGS) {
            InputStream in = plugin.getResource("templates/" + dir + "/" + fileName);
            if (in == null) {
                continue;
            }
            if (dir.equals(lang)) {
                closeQuietly(fallback);
                return in;
            }
            if (fallback == null) {
                fallback = in;
            } else {
                closeQuietly(in);
            }
        }
        return fallback;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 兜底流关闭失败可忽略
        }
    }

    /** 将 jar 内指定语言模板复制到目标文件（自动创建父目录）；目标已存在时覆盖；模板全部缺失返回 false。 */
    public static boolean saveTemplate(JavaPlugin plugin, String language, String fileName, File target)
            throws IOException {
        try (InputStream in = openTemplate(plugin, language, fileName)) {
            if (in == null) {
                return false;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }
}
