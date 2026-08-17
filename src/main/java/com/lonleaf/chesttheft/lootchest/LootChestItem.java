package com.lonleaf.chesttheft.lootchest;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.CrossPluginItemUtil;
import com.lonleaf.chesttheft.item.ItemManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 战利品箱内物品定义：引用 items/ 文件夹物品（id）、原版材质（material）或
 * 外部插件物品（id 使用插件前缀，如 "MMOItems:SWORD:example_item"），
 * 可附加数量/名称/Lore/CustomModelData。
 */
public class LootChestItem {
    private final String itemId;
    private final Material material;
    private final int amount;
    private final String name;
    private final List<String> lore;
    private final Integer customModelData;

    private LootChestItem(String itemId, Material material, int amount, String name,
                          List<String> lore, Integer customModelData) {
        this.itemId = itemId;
        this.material = material;
        this.amount = amount;
        this.name = name;
        this.lore = lore;
        this.customModelData = customModelData;
    }

    /** 从配置映射解析单个物品；id 与 material 都缺失或非法时返回 null（由调用方告警跳过）。 */
    public static LootChestItem from(Map<?, ?> map, String profileId, Logger logger) {
        String itemId = map.get("id") instanceof String s && !s.isBlank() ? s : null;
        String materialName = map.get("material") instanceof String m && !m.isBlank() ? m : null;
        if (itemId == null && materialName == null) {
            logger.log(Level.WARNING, Messages.getLog(Messages.LOG_LOOT_CHEST_ITEM_INVALID, profileId, map));
            return null;
        }
        int amount = Math.max(1, map.get("amount") instanceof Number n ? n.intValue() : 1);
        String name = map.get("name") instanceof String s2 ? s2 : null;
        List<String> lore = new ArrayList<>();
        if (map.get("lore") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String line) {
                    lore.add(line);
                }
            }
        }
        Integer customModelData = map.get("customModelData") instanceof Number n2 ? n2.intValue() : null;
        Material material = null;
        if (materialName != null) {
            try {
                material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.log(Level.WARNING, Messages.getLog(Messages.LOG_LOOT_CHEST_INVALID_MATERIAL, profileId, materialName));
            }
        }
        if (itemId == null && material == null) {
            return null;
        }
        return new LootChestItem(itemId, material, amount, name, lore, customModelData);
    }

    /** 构建物品；items 文件夹引用缺失或外部插件未安装时返回 null（调用方跳过，不影响其余物品）。 */
    public ItemStack build(ItemManager itemManager) {
        ItemStack item;
        if (itemId != null) {
            if (CrossPluginItemUtil.isExternalPluginId(itemId)) {
                // 外部插件物品：如 MMOItems:SWORD:example_item、ItemsAdder:ruby 等
                item = CrossPluginItemUtil.getItem(itemId, null);
                if (item == null) {
                    return null;
                }
                item.setAmount(Math.max(1, amount));
            } else {
                item = itemManager.create(itemId, amount);
                if (item == null) {
                    return null;
                }
            }
        } else {
            item = new ItemStack(material, amount);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            if (!lore.isEmpty()) {
                meta.setLore(lore.stream()
                        .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                        .collect(Collectors.toList()));
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
