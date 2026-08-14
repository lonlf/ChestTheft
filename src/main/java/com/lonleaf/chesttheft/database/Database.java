package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.inventory.ItemStack;

public interface Database {
    void init();

    boolean isLocked(BlockLocation location);

    /** 记录箱子锁定，保存锁的物品数据与上锁者 UUID（用于卸锁返还与钥匙配对）。 */
    void lock(BlockLocation location, ItemStack lockItem, String lockerUuid, String token);

    /** 返回上锁者 UUID，无记录时返回 null。 */
    String getLocker(BlockLocation location);

    /** 返回该锁的配对凭证（上锁时生成的随机值），无记录时返回 null。 */
    String getLockToken(BlockLocation location);

    /** 该锁是否已有配对钥匙。 */
    boolean hasPairedKey(BlockLocation location);

    /** 该锁已配对钥匙数 +1（钥匙配对时调用）。 */
    void increasePairedCount(BlockLocation location);

    /** 解除锁定并返回保存的锁物品，无记录时返回 null。 */
    ItemStack unlock(BlockLocation location);

    void close();
}
