package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.stream.Collectors;

/**
 * 物品管理：按物品定义构建特殊物品 ItemStack，以物品 ID 进行持久化标识与识别。
 * 配置加载由 ItemConfigManager 负责，持久化标签由 ItemTagger 负责。
 */
public class ItemManager {
    private final ItemTagger tagger;
    private final ItemConfigManager configManager;

    public ItemManager(ItemTagger tagger, ItemConfigManager configManager) {
        this.tagger = tagger;
        this.configManager = configManager;
    }

    /** 按物品 ID 构建特殊物品，ID 不存在时返回 null。 */
    public ItemStack create(String id, int amount) {
        ItemDefinition def = configManager.getDefinition(id);
        if (def == null) {
            return null;
        }
        ItemStack item = new ItemStack(def.getMaterial(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (def.getName() != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', def.getName()));
            }
            if (def.getCustomModelData() != null) {
                meta.setCustomModelData(def.getCustomModelData());
            }
            if (!def.getLore().isEmpty()) {
                meta.setLore(def.getLore().stream()
                        .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                        .collect(Collectors.toList()));
            }
            item.setItemMeta(meta);
        }
        tagger.tag(item, def.getId());
        return item;
    }

    /** 构建某类型的默认物品（该类型首个加载的定义），无定义时返回 null。 */
    public ItemStack createDefault(ItemType type, int amount) {
        String id = configManager.getDefaultId(type);
        return id == null ? null : create(id, amount);
    }

    /** 读取特殊物品 ID，非特殊物品返回 null。 */
    public String getId(ItemStack item) {
        return tagger.getId(item);
    }

    /** 通过 ID 解析物品类型，非特殊物品或定义缺失时返回 null。 */
    public ItemType getType(ItemStack item) {
        String id = tagger.getId(item);
        if (id == null) {
            return null;
        }
        ItemDefinition def = configManager.getDefinition(id);
        return def == null ? null : def.getType();
    }

    public boolean isType(ItemStack item, ItemType type) {
        return type == getType(item);
    }

    /** 将物品写入持久化标签，标记为指定 ID 的特殊物品。 */
    public void tag(ItemStack item, String id) {
        tagger.tag(item, id);
    }

    /** 将物品标记为该类型的默认特殊物品。 */
    public void tagAsDefault(ItemStack item, ItemType type) {
        String id = configManager.getDefaultId(type);
        if (id != null) {
            tagger.tag(item, id);
        }
    }

    /** 将钥匙与锁配对：把锁的位置写入钥匙的持久化存储。 */
    public void setPairedLock(ItemStack item, BlockLocation location) {
        tagger.setPairedLock(item, location);
    }

    /** 读取钥匙配对的锁位置，未配对返回 null。 */
    public BlockLocation getPairedLock(ItemStack item) {
        return tagger.getPairedLock(item);
    }

    /** 钥匙是否与该锁配对。 */
    public boolean isPairedTo(ItemStack item, BlockLocation location) {
        return tagger.isPairedTo(item, location);
    }
}
