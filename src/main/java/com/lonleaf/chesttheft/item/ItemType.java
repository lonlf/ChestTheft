package com.lonleaf.chesttheft.item;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 物品类型枚举，对应物品 PDC 中的标识标签。
 */
public enum ItemType {
    LOCK("lock", Material.IRON_INGOT),
    KEY("key", Material.GOLD_NUGGET),
    PICKER("picker", Material.FISHING_ROD);

    private final String tag;
    private final Material defaultMaterial;

    ItemType(String tag, Material defaultMaterial) {
        this.tag = tag;
        this.defaultMaterial = defaultMaterial;
    }

    public String getTag() {
        return tag;
    }

    public String getConfigKey() {
        return tag;
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
