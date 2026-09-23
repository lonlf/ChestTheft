package com.lonleaf.chesttheft.lootchest;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.display.DisplayEntityUtil;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 战利品箱管理器：创建/登记/查询/移除活动箱子，处理过期清理、玩家进服/换世界实体重发与 block 模式区块重载恢复。 */
public class LootChestManager {
    private final ChestTheft plugin;
    private final PluginConfig config;
    private final LootChestConfigManager configManager;
    /** 存在标记（识别本插件的战利品箱方块）。 */
    private final NamespacedKey blockKey;
    /** 元数据键：档案 id / 有效撬锁等级 / 创建时间 / 是否已开启 / 首次开启时间。 */
    private final NamespacedKey profileKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey createdKey;
    private final NamespacedKey lootedKey;
    private final NamespacedKey lootedAtKey;
    /** 已告警过的失效档案 id（避免区块重载时反复刷日志）。 */
    private final Set<String> missingProfilesReported = new HashSet<>();

    /** 全部活动箱子：箱子 UUID → 箱子。 */
    private final Map<UUID, LootChest> activeChests = new ConcurrentHashMap<>();
    /** block 模式反查：方块坐标 → 箱子 UUID。 */
    private final Map<BlockLocation, UUID> blockOwners = new ConcurrentHashMap<>();
    /** display 模式反查：纯客户端实体 ID（展示实体/交互载体） → 箱子 UUID。 */
    private final Map<Integer, UUID> displayEntityIds = new ConcurrentHashMap<>();
    /** 物品栏反查（display 虚拟物品栏与 block 容器物品栏）：物品栏 → 箱子 UUID。 */
    private final Map<Inventory, UUID> inventoryOwners = new ConcurrentHashMap<>();

    private BukkitTask expireTask;

    public LootChestManager(ChestTheft plugin, PluginConfig config, LootChestConfigManager configManager) {
        this.plugin = plugin;
        this.config = config;
        this.configManager = configManager;
        this.blockKey = new NamespacedKey(plugin, "loot_block");
        this.profileKey = new NamespacedKey(plugin, "loot_profile");
        this.levelKey = new NamespacedKey(plugin, "loot_level");
        this.createdKey = new NamespacedKey(plugin, "loot_created");
        this.lootedKey = new NamespacedKey(plugin, "loot_looted");
        this.lootedAtKey = new NamespacedKey(plugin, "loot_looted_at");
        startExpireTask();
    }

    /**
     * 创建战利品箱：合并配置物品与（可选）自然掉落，按 display-type 选择展示方式；
     * 物品为空或无法放置时返回 null。
     */
    public LootChest createChest(Location location, List<ItemStack> drops, LootChestProfile profile) {
        List<ItemStack> items = new ArrayList<>();
        if (profile != null) {
            items.addAll(profile.buildItems(configManager.getItemManager()));
        }
        if (profile == null || profile.isIncludeDrops()) {
            for (ItemStack drop : drops) {
                if (drop != null && !drop.getType().isAir()) {
                    items.add(drop.clone());
                }
            }
        }
        if (items.isEmpty()) {
            return null;
        }

        if (config.getLootChestDisplayType() == PluginConfig.LootChestDisplayType.BLOCK) {
            return createBlockChest(location, items, profile);
        }
        return createDisplayChest(location, items, profile);
    }

    /** display 模式：虚拟物品栏 + 纯客户端 BlockDisplay/Interaction（发包创建，服务端不建实体）。 */
    private LootChest createDisplayChest(Location location, List<ItemStack> items, LootChestProfile profile) {
        int size = Math.max(9, Math.min(54, (int) Math.ceil(items.size() / 9.0) * 9));
        String title = profile != null && profile.getTitle() != null
                ? ChatColor.translateAlternateColorCodes('&', profile.getTitle())
                : "Loot Chest";
        Inventory inventory = Bukkit.createInventory(null, size, title);
        fillInventory(inventory, items);
        Material material = profile != null ? profile.getDisplayMaterial() : Material.CHEST;
        // display 实体仅存在于客户端，服务端只有实体 ID：BlockDisplay 渲染箱子，Interaction 提供可点击 hitbox
        int displayId = DisplayEntityUtil.spawnDisplay(location, material);
        int interactId = DisplayEntityUtil.spawnInteraction(location);
        LootChest chest = new LootChest(location, inventory, profile);
        chest.setDisplayEntityId(displayId);
        chest.setInteractEntityId(interactId);
        if (config.isDebug()) {
            plugin.getLogger().info("[LootChest] display chest created at "
                    + location.getWorld().getName() + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                    + " | displayId=" + displayId + " interactId=" + interactId
                    + " uuid=" + chest.getUuid());
        }
        registerChest(chest);
        return chest;
    }

    /** block 模式：放置真实容器方块并把物品装入其容器物品栏，超容量部分掉落在方块上方。 */
    private LootChest createBlockChest(Location location, List<ItemStack> items, LootChestProfile profile) {
        Material material = profile != null && LootChestDisplay.isContainerMaterial(profile.getDisplayMaterial())
                ? profile.getDisplayMaterial() : Material.CHEST;
        Block block = LootChestDisplay.placeChestBlock(location, material, blockKey);
        if (block == null) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_LOOT_CHEST_NOT_CONTAINER,
                    location.getWorld().getName() + "," + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ(),
                    material.name()));
            return null;
        }
        Container container = (Container) block.getState();
        Inventory inventory = container.getInventory();
        int index = 0;
        for (ItemStack item : items) {
            if (index >= inventory.getSize()) {
                block.getWorld().dropItemNaturally(block.getLocation().clone().add(0.5, 0.5, 0.5), item);
            } else {
                inventory.setItem(index++, item);
            }
        }
        LootChest chest = new LootChest(block.getLocation(), inventory, profile);
        chest.setChestBlock(block);
        registerChest(chest);
        writeMetadata(chest);   // 元数据落盘：重启/区块重载后据此还原等级与过期计时
        return chest;
    }

    /** 按档案 ID 在指定位置召唤战利品箱（命令使用，仅含档案配置物品）；档案不存在时返回 null。 */
    public LootChest spawnChest(Location location, String profileId) {
        LootChestProfile profile = configManager.getProfileById(profileId);
        if (profile == null) {
            return null;
        }
        return createChest(location, List.of(), profile);
    }

    /** 全部档案 ID（命令补全使用）。 */
    public List<String> getProfileIds() {
        return configManager.getProfileIds();
    }

    /** 登记箱子到活动集合（block 模式同时登记方块坐标反查，display 模式登记实体 ID 与物品栏反查）。 */
    private void registerChest(LootChest chest) {
        activeChests.put(chest.getUuid(), chest);
        inventoryOwners.put(chest.getInventory(), chest.getUuid());
        if (chest.getChestBlock() != null) {
            blockOwners.put(BlockLocation.from(chest.getChestBlock()), chest.getUuid());
        } else if (chest.getDisplayEntityId() != -1) {
            displayEntityIds.put(chest.getDisplayEntityId(), chest.getUuid());
            if (chest.getInteractEntityId() != -1) {
                displayEntityIds.put(chest.getInteractEntityId(), chest.getUuid());
            }
        }
    }

    /** 把物品依次放入物品栏：容量不足时剩余物品自然掉落（block 模式容器有位置）；
     *  虚拟物品栏（display 模式）无位置时记录日志，避免静默丢失。 */
    private void fillInventory(Inventory inventory, List<ItemStack> items) {
        int index = 0;
        for (ItemStack item : items) {
            if (index >= inventory.getSize()) {
                Location location = inventory.getLocation();
                if (location != null && location.getWorld() != null) {
                    location.getWorld().dropItemNaturally(location, item);
                } else {
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_LOOT_CHEST_FULL_NO_DROP, item.getType()));
                }
                continue;
            }
            inventory.setItem(index++, item);
        }
    }

    /** 按真实方块查找箱子（block 模式）。 */
    public LootChest findByBlock(Block block) {
        UUID uuid = blockOwners.get(BlockLocation.from(block));
        return uuid != null ? activeChests.get(uuid) : null;
    }

    /** 按展示实体 ID 查找箱子（display 模式，PacketEvents 交互包使用）。 */
    public LootChest findByDisplayEntityId(int entityId) {
        UUID uuid = displayEntityIds.get(entityId);
        return uuid != null ? activeChests.get(uuid) : null;
    }

    /** 按物品栏查找箱子（关闭/打开事件使用）。 */
    public LootChest findByInventory(Inventory inventory) {
        UUID uuid = inventoryOwners.get(inventory);
        return uuid != null ? activeChests.get(uuid) : null;
    }

    /** 箱子是否仍在活动注册表中（主线程二次校验用；收包与执行之间存在移除窗口）。 */
    public boolean isActive(LootChest chest) {
        return chest != null && activeChests.containsKey(chest.getUuid());
    }

    /** 移除箱子：关闭查看者、清空物品栏、移除纯客户端展示实体/真实方块并播放粒子。 */
    public void removeChest(LootChest chest) {
        if (chest == null || activeChests.remove(chest.getUuid()) == null) {
            return;
        }
        inventoryOwners.remove(chest.getInventory());
        if (chest.getInventory() != null) {
            for (HumanEntity viewer : new ArrayList<>(chest.getInventory().getViewers())) {
                viewer.closeInventory();
            }
        }
        if (chest.getDisplayEntityId() != -1) {
            displayEntityIds.remove(chest.getDisplayEntityId());
            displayEntityIds.remove(chest.getInteractEntityId());
            DisplayEntityUtil.destroyEntity(
                    chest.getDisplayEntityId(), chest.getInteractEntityId());
        }
        if (chest.getChestBlock() != null) {
            blockOwners.remove(BlockLocation.from(chest.getChestBlock()));
            LootChestDisplay.removeChestBlock(chest.getChestBlock());
        }
        LootChestDisplay.playRemoveParticles(chest.getLocation());
    }

    /**
     * 区块加载同步：display 模式纯客户端实体重启后不保留，无需同步；
     * block 模式根据方块 PDC 恢复容器物品栏箱子（真实方块随区块保存），
     * 档案/等级/创建时间/已开状态一并从 PDC 还原，避免重启后撬锁门槛被绕过。
     */
    public void syncFromChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Container container)
                    || !container.getPersistentDataContainer().has(blockKey, PersistentDataType.STRING)) {
                continue;
            }
            BlockLocation loc = BlockLocation.from(state.getBlock());
            if (blockOwners.containsKey(loc)) {
                continue;
            }
            registerChest(restoreChest(state.getBlock(), container));
        }
    }

    /**
     * 启用时补扫全部已加载区块：重启后出生点等区块已加载，不会触发 ChunkLoadEvent，
     * 若不补扫则这些箱子不受破坏保护、不参与过期清理，且撬锁门槛会丢失。
     */
    public void scanLoadedChunks() {
        if (config.getLootChestDisplayType() != PluginConfig.LootChestDisplayType.BLOCK) {
            return;
        }
        int found = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int before = activeChests.size();
                syncFromChunk(chunk);
                found += activeChests.size() - before;
            }
        }
        if (found > 0) {
            plugin.getLogger().info("[ChestTheft] Restored " + found + " loot chest(s) from loaded chunks");
        }
    }

    /** 按方块 PDC 还原箱子：档案存在时以档案为准，档案缺失时用持久化等级守住撬锁门槛。 */
    private LootChest restoreChest(Block block, Container container) {
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        String profileId = pdc.get(profileKey, PersistentDataType.STRING);
        LootChestProfile profile = profileId == null || profileId.isEmpty()
                ? null : configManager.getProfileById(profileId);
        if (profileId != null && !profileId.isEmpty() && profile == null) {
            warnMissingProfileOnce(profileId);
        }
        LootChest chest = new LootChest(block.getLocation(), container.getInventory(), profile,
                pdc.getOrDefault(levelKey, PersistentDataType.INTEGER, -1),
                pdc.getOrDefault(createdKey, PersistentDataType.LONG, System.currentTimeMillis()),
                pdc.getOrDefault(lootedKey, PersistentDataType.BYTE, (byte) 0) == 1,
                pdc.getOrDefault(lootedAtKey, PersistentDataType.LONG, 0L));
        chest.setChestBlock(block);
        return chest;
    }

    /** 把箱子元数据写入方块 PDC（block 模式；display 模式无真实方块，跳过）。 */
    private void writeMetadata(LootChest chest) {
        Block block = chest.getChestBlock();
        if (block == null) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return;
        }
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        LootChestProfile profile = chest.getProfile();
        if (profile != null) {
            pdc.set(profileKey, PersistentDataType.STRING, profile.getId());
        } else {
            pdc.remove(profileKey);
        }
        pdc.set(levelKey, PersistentDataType.INTEGER, chest.getEffectiveLevel());
        pdc.set(createdKey, PersistentDataType.LONG, chest.getCreated());
        pdc.set(lootedKey, PersistentDataType.BYTE, (byte) (chest.isLooted() ? 1 : 0));
        pdc.set(lootedAtKey, PersistentDataType.LONG, chest.getLootedAt());
        container.update();   // 必须 update 才会写回方块数据
    }

    /** 标记已开启并回写持久化状态：重启后仍按 expire-time-opened 计过期。 */
    public void markLooted(LootChest chest) {
        if (chest == null) {
            return;
        }
        chest.markLooted();
        writeMetadata(chest);
    }

    /** 一次性告警：方块记录的档案已被删除或改名（箱子按 PDC 中的等级继续要求撬锁）。 */
    private void warnMissingProfileOnce(String profileId) {
        if (missingProfilesReported.add(profileId)) {
            plugin.getLogger().warning("[ChestTheft] Loot chest profile '" + profileId
                    + "' no longer exists; affected chests keep their persisted picklock level");
        }
    }

    /**
     * 玩家进服/换世界后重发其所在世界的全部活动 display 箱子（复用原有实体 ID）；
     * block 模式为真实方块，无需处理。
     */
    public void syncAllToPlayer(Player player) {
        if (config.getLootChestDisplayType() != PluginConfig.LootChestDisplayType.DISPLAY) {
            return;
        }
        for (LootChest chest : activeChests.values()) {
            if (chest.getDisplayEntityId() == -1
                    || !chest.getLocation().getWorld().equals(player.getWorld())) {
                continue;
            }
            DisplayEntityUtil.respawnTo(
                    chest.getDisplayEntityId(), chest.getInteractEntityId(), player);
        }
    }

    /** 启动过期清理定时任务（每秒检查一次）。 */
    private void startExpireTask() {
        expireTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickExpire, 20L, 20L);
    }

    /** 按过期时间清理：未开启的箱子用 expire-time，已开启的用 expire-time-opened，配置 0 不过期。 */
    private void tickExpire() {
        long now = System.currentTimeMillis();
        for (LootChest chest : activeChests.values()) {
            int expireSeconds = chest.isLooted() ? config.getLootChestExpireTimeOpened() : config.getLootChestExpireTime();
            if (expireSeconds <= 0) {
                continue;
            }
            long base = chest.isLooted() ? chest.getLootedAt() : chest.getCreated();
            if (now - base >= expireSeconds * 1000L) {
                removeChest(chest);
            }
        }
    }

    /** 插件停用清理：取消定时任务、移除纯客户端展示实体；block 模式的真实方块保留（物品随区块保存，重载后恢复）。 */
    public void clearAll() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
        for (LootChest chest : activeChests.values()) {
            if (chest.getDisplayEntityId() != -1) {
                DisplayEntityUtil.destroyEntity(
                        chest.getDisplayEntityId(), chest.getInteractEntityId());
            }
        }
        activeChests.clear();
        blockOwners.clear();
        displayEntityIds.clear();
        inventoryOwners.clear();
    }

    /** 活动箱子数量（调试/管理用途）。 */
    public int getActiveCount() {
        return activeChests.size();
    }

    /** reload 时重新加载 lootchest/ 配置档案。 */
    public void reloadConfig() {
        configManager.reload();
    }
}
