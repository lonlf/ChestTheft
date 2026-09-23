package com.lonleaf.chesttheft.service;

import com.lonleaf.chesttheft.database.LockStore;
import com.lonleaf.chesttheft.database.UnlockResult;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** 锁操作门面：全部读写走 {@link LockStore} 内存索引，避免主线程 JDBC 与数据库抖动的 fail-open。 */
public class ChestService {
    private final LockStore store;

    public ChestService(LockStore store) {
        this.store = store;
    }

    public boolean isLocked(Block block) {
        return store.isLocked(BlockLocation.from(block));
    }

    /** 上锁：生成随机配对凭证并写入数据库（卸锁重上后旧钥匙凭证失效）。返回是否上锁成功。 */
    public boolean lock(Block block, ItemStack lockItem, String lockerUuid, int level) {
        return store.lock(BlockLocation.from(block), lockItem, lockerUuid,
                UUID.randomUUID().toString(), level);
    }

    /** 返回上锁者 UUID，无记录时返回 null。 */
    public String getLocker(Block block) {
        return store.getLocker(BlockLocation.from(block));
    }

    /** 返回该锁的配对凭证（上锁时生成的随机值），无记录时返回 null。 */
    public String getLockToken(Block block) {
        return store.getLockToken(BlockLocation.from(block));
    }

    /** 返回锁等级（撬锁时选择对应难度配置），无记录或未配置时返回 0。 */
    public int getLockLevel(Block block) {
        return store.getLockLevel(BlockLocation.from(block));
    }

    public boolean hasPairedKey(Block block) {
        return store.hasPairedKey(BlockLocation.from(block));
    }

    public void increasePairedCount(Block block) {
        store.increasePairedCount(BlockLocation.from(block));
    }

    /** 返回该锁保存的物品数据（含触发器标签），无记录时返回 null。 */
    public ItemStack getLockItem(Block block) {
        return store.getLockItem(BlockLocation.from(block));
    }

    /** 解除锁定：三态返回（已删除/无记录/数据库失败），调用方据此决定是否补发默认锁。 */
    public UnlockResult unlock(Block block) {
        return store.unlock(BlockLocation.from(block));
    }
}
