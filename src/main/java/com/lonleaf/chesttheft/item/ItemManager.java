package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.stream.Collectors;

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

    /** 读取物品定义的锁等级，非特殊物品或定义缺失时返回 0。 */
    public int getLevel(ItemStack item) {
        String id = tagger.getId(item);
        if (id == null) {
            return 0;
        }
        ItemDefinition def = configManager.getDefinition(id);
        return def == null ? 0 : def.getLevel();
    }

    public void tag(ItemStack item, String id) {
        tagger.tag(item, id);
    }

    public void tagAsDefault(ItemStack item, ItemType type) {
        String id = configManager.getDefaultId(type);
        if (id != null) {
            tagger.tag(item, id);
        }
    }

    public void setPairedLock(ItemStack item, BlockLocation location, String token) {
        tagger.setPairedLock(item, location, token);
    }

    /** 读取钥匙配对的锁凭证，旧钥匙或无凭证返回 null。 */
    public String getPairedToken(ItemStack item) {
        return tagger.getPairedToken(item);
    }

    /** 读取钥匙配对的锁位置，未配对返回 null。 */
    public BlockLocation getPairedLock(ItemStack item) {
        return tagger.getPairedLock(item);
    }

    public boolean isPairedTo(ItemStack item, BlockLocation location) {
        return tagger.isPairedTo(item, location);
    }
}
