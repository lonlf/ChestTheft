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
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 战利品箱管理器：创建/登记/查询/移除活动箱子，处理过期清理、玩家进服/换世界实体重发与 block 模式区块重载恢复。 */
public class LootChestManager {
    private final ChestTheft plugin;
    private final PluginConfig config;
    private final LootChestConfigManager configManager;
    private final NamespacedKey blockKey;

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

    /** 把物品依次放入物品栏，放不下的丢弃（display 模式容量按物品数动态计算，通常不会触发）。 */
    private void fillInventory(Inventory inventory, List<ItemStack> items) {
        int index = 0;
        for (ItemStack item : items) {
            if (index >= inventory.getSize()) {
                break;
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
     * block 模式根据方块 PDC 恢复容器物品栏箱子（真实方块随区块保存）。
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
            LootChest chest = new LootChest(state.getLocation(), container.getInventory(), null);
            chest.setChestBlock(state.getBlock());
            registerChest(chest);
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
