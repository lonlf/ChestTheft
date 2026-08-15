package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class ItemTagger {
    private final NamespacedKey idKey;
    private final NamespacedKey lockKey;
    private final NamespacedKey tokenKey;

    public ItemTagger(NamespacedKey idKey, NamespacedKey lockKey, NamespacedKey tokenKey) {
        this.idKey = idKey;
        this.lockKey = lockKey;
        this.tokenKey = tokenKey;
    }

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

    public void setPairedLock(ItemStack item, BlockLocation location, String token) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(lockKey, PersistentDataType.STRING, location.toString());
            if (token != null) {
                meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token);
            }
            item.setItemMeta(meta);
        }
    }

    /** 读取钥匙配对的锁凭证，旧钥匙或无凭证返回 null。 */
    public String getPairedToken(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
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

    public boolean isPairedTo(ItemStack item, BlockLocation location) {
        BlockLocation paired = getPairedLock(item);
        return paired != null && paired.equals(location);
    }
}
