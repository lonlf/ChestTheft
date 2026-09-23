package com.lonleaf.chesttheft.service;

import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 材质包：启动时把 jar 内打包的 zip 释放到插件目录，已存在则跳过（不覆盖服主自改版本） */
public final class ResourcePackExporter {

    /** 随插件内置的材质包 zip（与仓库根目录同名文件保持一致，更新材质包后需重新复制并编译） */
    private static final String[] BUNDLED_PACKS = {
            "chesttheft-resourcepack.zip"
    };

    private ResourcePackExporter() {
    }

    /** 逐个释放缺失的内置材质包 */
    public static void exportIfMissing(JavaPlugin plugin) {
        for (String name : BUNDLED_PACKS) {
            exportIfMissing(plugin, name);
        }
    }

    /** 单个 zip：仅当插件目录下不存在同名文件时才从 jar 释放 */
    private static void exportIfMissing(JavaPlugin plugin, String name) {
        Path target = plugin.getDataFolder().toPath().resolve(name);
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_RESOURCE_PACK_DIR_FAIL, target.getParent(), e.getMessage()));
            return;
        }
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_RESOURCE_PACK_MISSING, name));
                return;
            }
            Files.copy(in, target);
            plugin.getLogger().info(Messages.getLog(Messages.LOG_RESOURCE_PACK_EXPORTED, target));
        } catch (IOException e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_RESOURCE_PACK_EXPORT_FAIL, name, e.getMessage()));
        }
    }
}
