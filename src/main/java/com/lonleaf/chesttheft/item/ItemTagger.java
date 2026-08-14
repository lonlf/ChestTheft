package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 特殊物品持久化标签模块：独立负责将物品写入持久化存储（PDC），
 * 以物品ID标识特殊物品（key / lock / picker），并负责钥匙与锁的配对记录。
 */
public class ItemTagger {
    private final NamespacedKey idKey;
    private final NamespacedKey lockKey;

    public ItemTagger(NamespacedKey idKey, NamespacedKey lockKey) {
        this.idKey = idKey;
        this.lockKey = lockKey;
    }

    /** 将物品写入持久化存储，标记为指定 ID 的特殊物品。 */
    public void tag(ItemStack item, String id) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
        }
    }

    /** 读取特殊物品 ID，非特殊物品返回 null。 */
    public String getId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /** 将钥匙与锁配对：把锁的位置写入钥匙的持久化存储。 */
    public void setPairedLock(ItemStack item, BlockLocation location) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(lockKey, PersistentDataType.STRING, location.toString());
            item.setItemMeta(meta);
        }
    }

    /** 读取钥匙配对的锁位置，未配对返回 null。 */
    public BlockLocation getPairedLock(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String data = meta.getPersistentDataContainer().get(lockKey, PersistentDataType.STRING);
        return data == null ? null : BlockLocation.parse(data);
    }

    /** 钥匙是否与该锁配对。 */
    public boolean isPairedTo(ItemStack item, BlockLocation location) {
        BlockLocation paired = getPairedLock(item);
        return paired != null && paired.equals(location);
    }
}
