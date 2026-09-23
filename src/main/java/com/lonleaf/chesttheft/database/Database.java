package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface Database {
    void init();

    /**
     * 载入全部锁记录（启动时构建内存索引）。失败抛 RuntimeException，由调用方决定中止启用或降级。
     */
    List<LockRecord> loadAllLocks();

    boolean isLocked(BlockLocation location);

    /**
     * 记录箱子锁定（保存锁物品数据与上锁者 UUID，用于卸锁返还与钥匙配对）；失败返回 false，调用方不应消耗锁物品。
     */
    boolean lock(BlockLocation location, ItemStack lockItem, String lockerUuid, String token, int level);

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

    /** 解除锁定并返还保存的锁物品；三态返回，区分"已删除 / 无记录 / 数据库失败"。 */
    UnlockResult unlock(BlockLocation location);

    /**
     * 记录一次临时授权（仅持久化型保护需要，供崩溃后启动清理）：同一 (插件, 位置, 玩家) 仅保留一条。
     *
     * @return 是否记录成功；失败时调用方应中止授权，避免"已授权但无记录"导致残留权限无法清理
     */
    boolean recordTempGrant(String pluginType, BlockLocation location, UUID playerUuid, String extra);

    /** 返回指定插件类型的全部临时授权记录（启动清理用）。 */
    List<TempGrantRecord> getTempGrants(String pluginType);

    /** 删除一条临时授权记录（对应权限已成功撤销）。 */
    void deleteTempGrant(String pluginType, BlockLocation location, UUID playerUuid);

    void close();
}
