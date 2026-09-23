package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 锁状态的内存权威索引：启动全量载入，读操作零数据库访问（DB 抖动不会退化成"全部未上锁"），
 * 写操作写穿（DB 成功才改索引）。多服共享同一 MySQL 时由外部定时调用 {@link #refresh()} 刷新。
 */
public class LockStore {

    private final Database database;
    private final Logger logger;
    /** 不可变快照整体替换：读无锁，写/刷新为 O(n) 拷贝（上锁/卸锁本身低频）。 */
    private volatile Map<BlockLocation, LockRecord> locks = Map.of();
    /** 载入失败且允许降级时为 true：所有可上锁方块一律视为已上锁（fail-closed）。 */
    private volatile boolean degraded;

    public LockStore(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** 启动全量载入；abortOnFailure=true 时失败抛出，由调用方中止插件启用。 */
    public void loadAll(boolean abortOnFailure) {
        try {
            locks = snapshot(database.loadAllLocks());
            degraded = false;
            logger.info(Messages.getLog(Messages.LOG_LOCK_INDEX_LOADED, locks.size()));
        } catch (RuntimeException e) {
            if (abortOnFailure) {
                throw e;
            }
            degraded = true;
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_LOCK_INDEX_LOAD_FAIL, e.getMessage()), e);
        }
    }

    /** read-through 模式下的定时刷新：失败保留旧索引，不改变已生效的锁定状态。 */
    public void refresh() {
        try {
            locks = snapshot(database.loadAllLocks());
            degraded = false;
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_LOCK_INDEX_LOAD_FAIL, e.getMessage()), e);
        }
    }

    /** 降级模式：锁状态未知，一律按已上锁处理。 */
    public boolean isDegraded() {
        return degraded;
    }

    public boolean isLocked(BlockLocation location) {
        return degraded || locks.containsKey(location);
    }

    public String getLockToken(BlockLocation location) {
        LockRecord record = locks.get(location);
        return record == null ? null : record.getLockToken();
    }

    public String getLocker(BlockLocation location) {
        LockRecord record = locks.get(location);
        return record == null ? null : record.getLockerUuid();
    }

    public int getLockLevel(BlockLocation location) {
        LockRecord record = locks.get(location);
        return record == null ? 0 : record.getLockLevel();
    }

    public boolean hasPairedKey(BlockLocation location) {
        LockRecord record = locks.get(location);
        return record != null && record.getPairedCount() > 0;
    }

    public ItemStack getLockItem(BlockLocation location) {
        LockRecord record = locks.get(location);
        return record == null ? null : AbstractDatabase.deserializeItem(record.getLockItemData());
    }

    public boolean lock(BlockLocation location, ItemStack lockItem, String lockerUuid, String token, int level) {
        if (degraded) {
            return false;
        }
        if (!database.lock(location, lockItem, lockerUuid, token, level)) {
            return false;
        }
        Map<BlockLocation, LockRecord> next = new HashMap<>(locks);
        next.put(location, new LockRecord(location, AbstractDatabase.serializeItem(lockItem),
                lockerUuid, token, level, 0));
        locks = Map.copyOf(next);
        return true;
    }

    public UnlockResult unlock(BlockLocation location) {
        UnlockResult result = database.unlock(location);
        if (!result.isDeleted()) {
            return result;
        }
        Map<BlockLocation, LockRecord> next = new HashMap<>(locks);
        next.remove(location);
        locks = Map.copyOf(next);
        return result;
    }

    public void increasePairedCount(BlockLocation location) {
        LockRecord current = locks.get(location);
        if (current == null) {
            return;
        }
        database.increasePairedCount(location);
        Map<BlockLocation, LockRecord> next = new HashMap<>(locks);
        next.put(location, current.withPairedCount(current.getPairedCount() + 1));
        locks = Map.copyOf(next);
    }

    private static Map<BlockLocation, LockRecord> snapshot(List<LockRecord> rows) {
        Map<BlockLocation, LockRecord> next = new HashMap<>(Math.max(16, rows.size() * 2));
        for (LockRecord row : rows) {
            next.put(row.getLocation(), row);
        }
        return Map.copyOf(next);
    }
}
