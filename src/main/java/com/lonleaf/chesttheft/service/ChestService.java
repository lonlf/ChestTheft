package com.lonleaf.chesttheft.service;

import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 上锁状态服务：封装数据库访问，供监听器调用。
 */
public class ChestService {
    private final Database database;

    public ChestService(Database database) {
        this.database = database;
    }

    public boolean isLocked(Block block) {
        return database.isLocked(BlockLocation.from(block));
    }

    /** 上锁：生成随机配对凭证并写入数据库（卸锁重上后旧钥匙凭证失效）。 */
    public void lock(Block block, ItemStack lockItem, String lockerUuid) {
        database.lock(BlockLocation.from(block), lockItem, lockerUuid, UUID.randomUUID().toString());
    }

    /** 返回上锁者 UUID，无记录时返回 null。 */
    public String getLocker(Block block) {
        return database.getLocker(BlockLocation.from(block));
    }

    /** 返回该锁的配对凭证（上锁时生成的随机值），无记录时返回 null。 */
    public String getLockToken(Block block) {
        return database.getLockToken(BlockLocation.from(block));
    }

    /** 该锁是否已有配对钥匙。 */
    public boolean hasPairedKey(Block block) {
        return database.hasPairedKey(BlockLocation.from(block));
    }

    /** 该锁已配对钥匙数 +1（钥匙配对时调用）。 */
    public void increasePairedCount(Block block) {
        database.increasePairedCount(BlockLocation.from(block));
    }

    /** 解除锁定并返回保存的锁物品，无记录时返回 null。 */
    public ItemStack unlock(Block block) {
        return database.unlock(BlockLocation.from(block));
    }
}
