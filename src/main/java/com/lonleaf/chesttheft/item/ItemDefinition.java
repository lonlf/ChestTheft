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
    /** 外部插件物品 ID（material 带插件前缀时解析得到），未配置时为 null。 */
    private final String externalId;
    private final Integer customModelData;
    private final int level;
    private final List<String> lore;
    /** 锁物品自带的多个触发器配置段（triggers.<id>.type/actions），未配置时为 null。 */
    private final ConfigurationSection triggers;
    /** 上锁完成后自动给予的配对钥匙物品 ID；未配置（null/空）时不给予。 */
    private final String giveKey;

    private ItemDefinition(String id, ItemType type, String name, Material material, String externalId,
                           Integer customModelData, int level, List<String> lore,
                           ConfigurationSection triggers, String giveKey) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.material = material;
        this.externalId = externalId;
        this.customModelData = customModelData;
        this.level = level;
        this.lore = lore;
        this.triggers = triggers;
        this.giveKey = giveKey;
    }

    /** 从配置段解析物品定义；type 缺失或非法时返回 null（由调用方跳过并告警）。 */
    public static ItemDefinition from(String id, ConfigurationSection section, Logger logger) {
        ItemType type = section.getString("type") == null ? null : ItemType.fromName(section.getString("type"));
        if (type == null) {
            logger.log(Level.WARNING, Messages.getLog(Messages.LOG_ITEM_INVALID_TYPE, id));
            return null;
        }
        String name = section.getString("name");
        // material 可写原版材质或外部插件物品 ID（如 "ItemsAdder:ruby"）；后者解析失败时回退该类型默认材质
        String materialName = section.getString("material");
        String externalId = null;
        Material material = null;
        if (materialName != null && !materialName.isBlank()) {
            if (CrossPluginItemUtil.isExternalPluginId(materialName)) {
                externalId = materialName.trim();
            } else {
                try {
                    material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    logger.log(Level.WARNING, Messages.getLog(Messages.LOG_ITEM_INVALID_MATERIAL,
                            id, materialName, type.getDefaultMaterial()));
                }
            }
        }
        if (material == null) {
            material = type.getDefaultMaterial();
        }
        Integer customModelData = section.contains("customModelData") ? section.getInt("customModelData") : null;
        int level = Math.max(0, section.getInt("level", 0));
        List<String> lore = section.getStringList("lore");
        ConfigurationSection triggers = section.getConfigurationSection("triggers");
        String giveKey = section.getString("give-key");
        return new ItemDefinition(id, type, name, material, externalId, customModelData, level, lore, triggers, giveKey);
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

    /** 兜底材质：未用外部插件物品时即本体材质，否则仅作解析失败时的回退。 */
    public Material getMaterial() {
        return material;
    }

    /** 外部插件物品 ID（material 带插件前缀时），未配置为 null；以其为基底构建，解析失败回退 {@link #getMaterial()}。 */
    public String getExternalId() {
        return externalId;
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

    /** 锁物品自带的多个触发器配置段（triggers.<id>.type/actions），未配置时返回 null。 */
    public ConfigurationSection getTriggers() {
        return triggers;
    }

    /** 上锁完成后自动给予的配对钥匙物品 ID；未配置（null/空）时不给予。 */
    public String getGiveKey() {
        return giveKey;
    }
}
