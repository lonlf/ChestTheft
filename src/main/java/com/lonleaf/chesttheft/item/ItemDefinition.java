package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ItemDefinition {
    private final String id;
    private final ItemType type;
    private final String name;
    private final Material material;
    private final Integer customModelData;
    private final int level;
    private final List<String> lore;

    private ItemDefinition(String id, ItemType type, String name, Material material,
                           Integer customModelData, int level, List<String> lore) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.material = material;
        this.customModelData = customModelData;
        this.level = level;
        this.lore = lore;
    }

    /** 从配置段解析物品定义；type 缺失或非法时返回 null（由调用方跳过并告警）。 */
    public static ItemDefinition from(String id, ConfigurationSection section, Logger logger) {
        ItemType type = section.getString("type") == null ? null : ItemType.fromName(section.getString("type"));
        if (type == null) {
            logger.log(Level.WARNING, Messages.getLog(Messages.LOG_ITEM_INVALID_TYPE, id));
            return null;
        }
        String name = section.getString("name");
        String materialName = section.getString("material", type.getDefaultMaterial().name());
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, Messages.getLog(Messages.LOG_ITEM_INVALID_MATERIAL,
                    id, materialName, type.getDefaultMaterial()));
            material = type.getDefaultMaterial();
        }
        Integer customModelData = section.contains("customModelData") ? section.getInt("customModelData") : null;
        int level = Math.max(0, section.getInt("level", 0));
        List<String> lore = section.getStringList("lore");
        return new ItemDefinition(id, type, name, material, customModelData, level, lore);
    }

    public String getId() {
        return id;
    }

    public ItemType getType() {
        return type;
    }

    /** 显示名称，null 表示不设置。 */
    public String getName() {
        return name;
    }

    public Material getMaterial() {
        return material;
    }

    /** 自定义模型数据，null 表示不设置。 */
    public Integer getCustomModelData() {
        return customModelData;
    }

    /** 锁等级（撬锁时选择对应难度配置），未配置时返回 0。 */
    public int getLevel() {
        return level;
    }

    public List<String> getLore() {
        return lore;
    }
}
