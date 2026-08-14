package com.lonleaf.chesttheft.service;

import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

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

    public void lock(Block block, ItemStack lockItem, String lockerUuid) {
        database.lock(BlockLocation.from(block), lockItem, lockerUuid);
    }

    /** 返回上锁者 UUID，无记录时返回 null。 */
    public String getLocker(Block block) {
        return database.getLocker(BlockLocation.from(block));
    }

    /** 解除锁定并返回保存的锁物品，无记录时返回 null。 */
    public ItemStack unlock(Block block) {
        return database.unlock(BlockLocation.from(block));
    }
}
