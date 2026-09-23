package com.lonleaf.chesttheft.lootchest;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.minigame.GameManager;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/** 战利品箱监听：生物死亡掉落转换、display 模式交互（PacketEvents 收包）、开启/关闭保留逻辑与方块保护。 */
public class LootChestListener implements Listener, PacketListener {
    private final ChestTheft plugin;
    private final PluginConfig config;
    private final LootChestManager manager;
    private final LootChestConfigManager configManager;
    /** 撬锁小游戏（level >= 0 的战利品箱需先撬锁才能打开）。 */
    private final GameManager gameManager;
    /** 按撬锁等级取对应的小游戏配置（gamelevel/）。 */
    private final LockConfigManager lockConfigManager;

    public LootChestListener(ChestTheft plugin, PluginConfig config,
                             LootChestManager manager, LootChestConfigManager configManager,
                             GameManager gameManager, LockConfigManager lockConfigManager) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.configManager = configManager;
        this.gameManager = gameManager;
        this.lockConfigManager = lockConfigManager;
        // display 模式交互收包：Interaction 为纯客户端实体，须在 PacketEvents 收包侧监听 INTERACT_ENTITY
        PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.LOW);
    }

    // ==================== 死亡掉落转换（基于 drops 配置） ====================

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (config.isVanillaDrop()) {
            return;
        }
        if (event.getEntity() instanceof Player || isExcludedWorld(event.getEntity().getWorld().getName())) {
            return;
        }
        // 确定实体标识符：MythicMobs 生物使用 "mythicmobs:<mob名称>"，否则用 Bukkit 实体类型名（小写）
        String entityIdentifier = resolveEntityIdentifier(event);
        if (entityIdentifier == null) return;

        LootChestProfile profile = configManager.getProfileByEntity(entityIdentifier);
        if (profile == null) {
            return;
        }
        double probability = profile.getDropProbability(entityIdentifier);
        if (Math.random() > probability) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        // 判空交由 createChest：其内部 buildItems() 物品池与自然掉落合并后为空时返回 null。
        // 此处不再独立调用 buildItems()，避免同一档案的物品池被独立随机两次（结果不一致且浪费）
        LootChest chest = manager.createChest(event.getEntity().getLocation(), drops, profile);
        if (chest != null) {
            drops.clear();
        }
    }

    /**
     * 解析实体标识符：MythicMobs 生物返回 "mythicmobs:<内部名称>"，否则返回实体类型名小写形式。
     */
    private String resolveEntityIdentifier(EntityDeathEvent event) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
            try {
                var optMob = MythicBukkit.inst().getMobManager().getActiveMob(event.getEntity().getUniqueId());
                if (optMob != null && optMob.isPresent()) {
                    return "mythicmobs:" + optMob.get().getType().getInternalName();
                }
            } catch (Exception ignored) {
            }
        }
        return event.getEntityType().name().toLowerCase(Locale.ROOT);
    }

    // ==================== display 模式交互（PacketEvents 收包） ====================

    /**
     * display 模式交互收包：按实体 ID 反查箱子后调度主线程开箱或撬锁（netty 线程不能直接调 Bukkit API）。
     * 解析整体 try-catch：畸形包异常在 netty 线程吞掉并记录，避免上抛导致客户端连接断开。
     */
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        if (config.getLootChestDisplayType() != PluginConfig.LootChestDisplayType.DISPLAY) {
            return;
        }
        try {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.INTERACT
                    && wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            LootChest chest = manager.findByDisplayEntityId(wrapper.getEntityId());
            if (chest == null) {
                // 收包但未登记：可能是纯客户端实体未同步（进服/换世界后未重发）或遗留交互
                if (config.isDebug()) {
                    plugin.getLogger().info("[LootChest] interact entity not registered: id="
                            + wrapper.getEntityId() + " from " + player.getName());
                }
                return;
            }
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> handleInteract(player, chest));
        } catch (Exception e) {
            // netty 线程解析畸形包异常：吞掉并记录，避免上抛导致客户端连接断开
            if (config.isDebug()) {
                plugin.getLogger().warning("解析战利品箱交互包失败: " + e.getMessage());
            }
        }
    }

    /** 主线程执行开箱/撬锁（由 onPacketReceive 调度）。 */
    private void handleInteract(Player player, LootChest chest) {
        // 二次校验：收包到主线程执行之间箱子可能已被移除（过期清理/插件移除），不再处理；
        // 玩家已持有该箱子物品栏界面时不重复打开（防连点叠加窗口）
        if (!manager.isActive(chest) || chest.getInventory().getViewers().contains(player)) {
            return;
        }
        if (config.isDebug()) {
            plugin.getLogger().info("[LootChest] interact matched chest uuid=" + chest.getUuid()
                    + " at " + chest.getLocation().getBlockX() + "," + chest.getLocation().getBlockY() + "," + chest.getLocation().getBlockZ());
        }
        if (!withinInteractionRange(player, chest.getLocation())) {
            return;
        }
        if (chest.getEffectiveLevel() >= 0) {
            // level >= 0 的战利品箱需要先撬锁，成功后由回调打开（档案被删除时仍按持久化等级要求撬锁）
            startPicklock(player, chest);
            return;
        }
        LootChestDisplay.playOpenEffects(plugin, chest, player);
        chest.open(player);
    }

    // ==================== 纯客户端实体重发 ====================

    /** 玩家进服：重发其所在世界的全部活动 display 箱子（纯客户端实体需逐玩家发包）。 */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.syncAllToPlayer(event.getPlayer());
    }

    /** 玩家换世界：重发新世界的活动 display 箱子（客户端换世界会清空实体）。 */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        manager.syncAllToPlayer(event.getPlayer());
    }

    // ==================== 开启 / 关闭保留逻辑 ====================

    /** block 模式：level >= 0 的战利品箱右键方块时拦截原版打开，改为发起撬锁。 */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        LootChest chest = manager.findByBlock(block);
        if (chest == null || chest.getEffectiveLevel() < 0) {
            return;
        }
        event.setCancelled(true);
        startPicklock(event.getPlayer(), chest);
    }

    /** 发起战利品箱撬锁：按有效等级取对应小游戏配置（档案缺失时用持久化等级），成功后打开该箱子。 */
    private void startPicklock(Player player, LootChest chest) {
        int level = chest.getEffectiveLevel();
        if (level < 0 || gameManager.isPlaying(player)) {
            return;
        }
        if (!player.hasPermission("chesttheft.use")) {
            Messages.send(player, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return;
        }
        Block target = chest.getLocation().getBlock();
        GameConfig levelConfig = lockConfigManager.getGameConfig(level);
        gameManager.startGame(player, target, levelConfig, () -> {
            LootChestDisplay.playOpenEffects(plugin, chest, player);
            chest.open(player);
        });
    }

    /** block 模式原版打开真实箱子时标记为已开启并回写 PDC；display 模式由 open() 标记，重复标记无害。 */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        LootChest chest = manager.findByInventory(event.getInventory());
        if (chest != null) {
            manager.markLooted(chest);
        }
    }

    /** 关闭后：箱子已取空 → 移除；未取空时按 keep 配置决定是否保留（保留则之后可再次打开）。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        LootChest chest = manager.findByInventory(event.getInventory());
        if (chest == null) {
            return;
        }
        boolean empty = chest.isEmpty();
        if (empty || !config.isLootChestKeep()) {
            // keep=false 且剩余物品需掉落时，先掉落再移除（removeChest 会清空物品栏）
            if (!empty && config.isLootChestDropRemaining()) {
                dropRemaining(chest);
            }
            manager.removeChest(chest);
        }
    }

    /** 把箱子内剩余物品掉落到箱子位置（keep=false 且 drop-remaining=true 时调用）。 */
    private void dropRemaining(LootChest chest) {
        Inventory inventory = chest.getInventory();
        Location loc = chest.getLocation();
        if (inventory == null || loc == null || loc.getWorld() == null) {
            return;
        }
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            loc.getWorld().dropItemNaturally(loc, item);
        }
        inventory.clear();
    }

    // ==================== block 模式方块保护 ====================

    /** 阻止玩家破坏战利品箱方块（取空后由插件自动移除）。 */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (manager.findByBlock(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** 爆炸不破坏战利品箱方块（避免物品外泄与记录丢失）。 */
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (config.getLootChestDisplayType() != PluginConfig.LootChestDisplayType.BLOCK) {
            return;
        }
        event.blockList().removeIf(block -> manager.findByBlock(block) != null);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (config.getLootChestDisplayType() != PluginConfig.LootChestDisplayType.BLOCK) {
            return;
        }
        event.blockList().removeIf(block -> manager.findByBlock(block) != null);
    }

    // ==================== 区块加载同步（block 模式） ====================

    /** 区块加载（含服务器重启后）时恢复真实箱子方块与箱子的绑定（display 模式为纯客户端实体，无需区块同步）。 */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.syncFromChunk(event.getChunk());
    }

    // ==================== 内部工具 ====================

    private boolean isExcludedWorld(String worldName) {
        for (String excluded : config.getLootChestExcludedWorlds()) {
            if (excluded != null && excluded.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    /** 距离判定：以箱子所在方块中心为基准（display 模式配置的交互范围）。 */
    private boolean withinInteractionRange(Player player, Location location) {
        if (player.getWorld() != location.getWorld()) {
            return false;
        }
        double range = config.getLootChestInteractionRange();
        Location center = location.clone().add(0.5, 0.5, 0.5);
        return player.getLocation().distanceSquared(center) <= range * range;
    }
}
