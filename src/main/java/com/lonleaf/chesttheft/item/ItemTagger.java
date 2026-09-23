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
    /** 锁物品自带的触发器配置（YAML 字符串），上锁时写入并随物品持久化。 */
    private final NamespacedKey triggerKey;

    public ItemTagger(NamespacedKey idKey, NamespacedKey lockKey, NamespacedKey tokenKey,
                      NamespacedKey triggerKey) {
        this.idKey = idKey;
        this.lockKey = lockKey;
        this.tokenKey = tokenKey;
        this.triggerKey = triggerKey;
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

    /** 写入钥匙配对信息（配对凭证必填：钥匙可用性依赖 token 与锁当前凭证一致，凭证缺失的配对无意义）。 */
    public void setPairedLock(ItemStack item, BlockLocation location, String token) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(lockKey, PersistentDataType.STRING, location.toString());
            meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token);
            item.setItemMeta(meta);
        }
    }

    /** 清除钥匙的配对信息（卸锁后锁已移除，配对失效恢复为未配对状态）。 */
    public void clearPairedLock(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(lockKey);
        meta.getPersistentDataContainer().remove(tokenKey);
        item.setItemMeta(meta);
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

    /** 写入锁物品的触发器配置（YAML 字符串）。 */
    public void setLockTrigger(ItemStack item, String data) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(triggerKey, PersistentDataType.STRING, data);
            item.setItemMeta(meta);
        }
    }

    /** 读取锁物品的触发器配置，未配置时返回 null。 */
    public String getLockTrigger(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(triggerKey, PersistentDataType.STRING);
    }
}
