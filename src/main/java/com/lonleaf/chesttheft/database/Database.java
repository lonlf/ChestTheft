package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.inventory.ItemStack;

public interface Database {
    void init();

    boolean isLocked(BlockLocation location);

    /** 记录箱子锁定，保存锁的物品数据与上锁者 UUID（用于卸锁返还与钥匙配对）。 */
    void lock(BlockLocation location, ItemStack lockItem, String lockerUuid, String token, int level);

    /** 返回上锁者 UUID，无记录时返回 null。 */
    String getLocker(BlockLocation location);

    /** 返回该锁的配对凭证（上锁时生成的随机值），无记录时返回 null。 */
    String getLockToken(BlockLocation location);

    /** 返回锁等级（撬锁时选择对应难度配置），无记录或未配置时返回 0。 */
    int getLockLevel(BlockLocation location);

    boolean hasPairedKey(BlockLocation location);

    /** 该锁已配对钥匙数 +1（钥匙配对时调用）。 */
    void increasePairedCount(BlockLocation location);

    /** 返回该锁保存的物品数据（含触发器标签），无记录时返回 null，不删除记录。 */
    ItemStack getLockItem(BlockLocation location);

    /** 解除锁定并返回保存的锁物品，无记录时返回 null。 */
    ItemStack unlock(BlockLocation location);

    void close();
}
