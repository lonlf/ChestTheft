package com.lonleaf.chesttheft.service;

import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ChestService {
    private final Database database;

    public ChestService(Database database) {
        this.database = database;
    }

    public boolean isLocked(Block block) {
        return database.isLocked(BlockLocation.from(block));
    }

    /** 上锁：生成随机配对凭证并写入数据库（卸锁重上后旧钥匙凭证失效）。返回是否上锁成功。 */
    public boolean lock(Block block, ItemStack lockItem, String lockerUuid, int level) {
        return database.lock(BlockLocation.from(block), lockItem, lockerUuid, UUID.randomUUID().toString(), level);
    }

    /** 返回上锁者 UUID，无记录时返回 null。 */
    public String getLocker(Block block) {
        return database.getLocker(BlockLocation.from(block));
    }

    /** 返回该锁的配对凭证（上锁时生成的随机值），无记录时返回 null。 */
    public String getLockToken(Block block) {
        return database.getLockToken(BlockLocation.from(block));
    }

    /** 返回锁等级（撬锁时选择对应难度配置），无记录或未配置时返回 0。 */
    public int getLockLevel(Block block) {
        return database.getLockLevel(BlockLocation.from(block));
    }

    public boolean hasPairedKey(Block block) {
        return database.hasPairedKey(BlockLocation.from(block));
    }

    public void increasePairedCount(Block block) {
        database.increasePairedCount(BlockLocation.from(block));
    }

    /** 返回该锁保存的物品数据（含触发器标签），无记录时返回 null。 */
    public ItemStack getLockItem(Block block) {
        return database.getLockItem(BlockLocation.from(block));
    }

    /** 解除锁定并返回保存的锁物品，无记录时返回 null。 */
    public ItemStack unlock(Block block) {
        return database.unlock(BlockLocation.from(block));
    }
}
