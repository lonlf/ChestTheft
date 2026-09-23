package com.lonleaf.chesttheft.lootchest;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** 单个战利品箱：持有物品栏（display 为虚拟物品栏，block 为真实箱子物品栏）、展示实体 ID 与开启状态。 */
public class LootChest {
    private final UUID uuid;
    private volatile Location location;
    private final LootChestProfile profile;
    /** 有效撬锁等级（-1 = 无需撬锁）：档案存在时取档案等级，档案缺失时取持久化元数据。 */
    private final int level;
    private final long created;
    private volatile boolean looted;
    private volatile long lootedAt;
    private volatile Inventory inventory;
    private volatile int displayEntityId = -1;
    private volatile int interactEntityId = -1;
    private volatile Block chestBlock;

    public LootChest(Location location, Inventory inventory, LootChestProfile profile) {
        this(location, inventory, profile, profile == null ? -1 : profile.getLevel(),
                System.currentTimeMillis(), false, 0L);
    }

    /** 恢复用构造：按持久化元数据还原等级/创建时间/已开状态（区块重载或服务器重启后）。 */
    public LootChest(Location location, Inventory inventory, LootChestProfile profile,
                     int level, long created, boolean looted, long lootedAt) {
        this.uuid = UUID.randomUUID();
        this.location = location.clone();
        this.inventory = inventory;
        this.profile = profile;
        this.level = level;
        this.created = created;
        this.looted = looted;
        this.lootedAt = lootedAt;
    }

    /** 打开箱子（display 模式调用）；block 模式由原版交互打开。 */
    public void open(Player player) {
        looted = true;
        lootedAt = System.currentTimeMillis();
        player.openInventory(inventory);
    }

    /** 标记为已开启（block 模式在原版打开时由 InventoryOpenEvent 调用）。 */
    public void markLooted() {
        looted = true;
        lootedAt = System.currentTimeMillis();
    }

    public boolean isEmpty() {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    public UUID getUuid() {
        return uuid;
    }

    /** 箱子所在世界坐标（方块格）。 */
    public Location getLocation() {
        return location;
    }

    public BlockLocation getBlockLocation() {
        return BlockLocation.from(location);
    }

    /** 配置档案，未配置时（default 缺失）为 null。 */
    public LootChestProfile getProfile() {
        return profile;
    }

    /** 有效撬锁等级（-1 = 无需撬锁）；档案被删除时仍由持久化元数据保持门槛。 */
    public int getEffectiveLevel() {
        return level;
    }

    public long getCreated() {
        return created;
    }

    /** 是否已被玩家开启过（决定过期时间按开启前/后计算）。 */
    public boolean isLooted() {
        return looted;
    }

    /** 首次被开启的时间戳（毫秒），未开启时为 0。 */
    public long getLootedAt() {
        return lootedAt;
    }

    public Inventory getInventory() {
        return inventory;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /** display 模式的展示实体 ID，block 模式为 -1。 */
    public int getDisplayEntityId() {
        return displayEntityId;
    }

    void setDisplayEntityId(int displayEntityId) {
        this.displayEntityId = displayEntityId;
    }

    /** display 模式的交互载体实体 ID（提供可点击 hitbox），block 模式为 -1。 */
    public int getInteractEntityId() {
        return interactEntityId;
    }

    void setInteractEntityId(int interactEntityId) {
        this.interactEntityId = interactEntityId;
    }

    /** block 模式的真实箱子方块，display 模式为 null。 */
    public Block getChestBlock() {
        return chestBlock;
    }

    void setChestBlock(Block chestBlock) {
        this.chestBlock = chestBlock;
    }
}
