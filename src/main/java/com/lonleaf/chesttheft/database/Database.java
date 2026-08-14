package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.inventory.ItemStack;

public interface Database {
    void init();

    boolean isLocked(BlockLocation location);

    /** 记录箱子锁定，保存锁的物品数据与上锁者 UUID（用于卸锁返还与钥匙配对）。 */
    void lock(BlockLocation location, ItemStack lockItem, String lockerUuid);

    /** 返回上锁者 UUID，无记录时返回 null。 */
    String getLocker(BlockLocation location);

    /** 解除锁定并返回保存的锁物品，无记录时返回 null。 */
    ItemStack unlock(BlockLocation location);

    void close();
}
