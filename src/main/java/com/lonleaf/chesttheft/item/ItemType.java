package com.lonleaf.chesttheft.item;

import org.bukkit.Material;

import java.util.Locale;

public enum ItemType {
    // configPath：jar 内资源路径（lock/key 按子文件夹组织，picker 直接放 items 下）
    LOCK("lock", "items/lock/lock.yml", Material.IRON_INGOT),
    KEY("key", "items/key/key.yml", Material.GOLD_NUGGET),
    PICKER("picker", "items/picker.yml", Material.FISHING_ROD);

    private final String tag;
    private final String configPath;
    private final Material defaultMaterial;

    ItemType(String tag, String configPath, Material defaultMaterial) {
        this.tag = tag;
        this.configPath = configPath;
        this.defaultMaterial = defaultMaterial;
    }

    public String getTag() {
        return tag;
    }

    public String getConfigKey() {
        return tag;
    }

    /** jar 内该类型的默认配置文件路径（saveResource 用）。 */
    public String getConfigPath() {
        return configPath;
    }

    public Material getDefaultMaterial() {
        return defaultMaterial;
    }

    public static ItemType fromTag(String tag) {
        for (ItemType type : values()) {
            if (type.tag.equals(tag)) {
                return type;
            }
        }
        return null;
    }

    public static ItemType fromName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
