package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;

/** 一条锁记录的不可变快照：锁物品以序列化串保存，按需反序列化，避免内存里长驻 ItemStack。 */
public final class LockRecord {

    private final BlockLocation location;
    private final String lockItemData;
    private final String lockerUuid;
    private final String lockToken;
    private final int lockLevel;
    private final int pairedCount;

    public LockRecord(BlockLocation location, String lockItemData, String lockerUuid,
                      String lockToken, int lockLevel, int pairedCount) {
        this.location = location;
        this.lockItemData = lockItemData == null ? "" : lockItemData;
        this.lockerUuid = lockerUuid;
        this.lockToken = lockToken;
        this.lockLevel = lockLevel;
        this.pairedCount = pairedCount;
    }

    public BlockLocation getLocation() {
        return location;
    }

    public String getLockItemData() {
        return lockItemData;
    }

    public String getLockerUuid() {
        return lockerUuid;
    }

    public String getLockToken() {
        return lockToken;
    }

    public int getLockLevel() {
        return lockLevel;
    }

    public int getPairedCount() {
        return pairedCount;
    }

    public LockRecord withPairedCount(int count) {
        return new LockRecord(location, lockItemData, lockerUuid, lockToken, lockLevel, count);
    }
}
