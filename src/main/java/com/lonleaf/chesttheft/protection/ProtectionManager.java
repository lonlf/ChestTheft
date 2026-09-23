package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.database.UnlockResult;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.database.Database;
import com.lonleaf.chesttheft.event.ChestInteractEvent;
import com.lonleaf.chesttheft.event.ChestLockEvent;
import com.lonleaf.chesttheft.event.ChestOpenEvent;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * 外部保护兼容统一入口：装配各保护插件适配器，统一调度受保护情况下的交互/上锁判定
 * 与打开容器的事件内临时授权（各适配器分发后，打开动作完成下一 tick 统一收回）。
 * 授权不持久化：事件内瞬时生效；持久化型保护授权前落库，崩溃后启动清理恢复，附件型不落库。
 */
public class ProtectionManager implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ChestService chestService;
    private final ItemManager itemManager;
    private final Database database;
    /** 已装配的适配器（对应保护插件已安装时才创建，避免类加载解析插件类型失败）。 */
    private final List<ProtectionAdapter> adapters = new ArrayList<>();

    public ProtectionManager(Plugin plugin, PluginConfig config, ChestService chestService,
                             ItemManager itemManager, Database database) {
        this.plugin = plugin;
        this.config = config;
        this.chestService = chestService;
        this.itemManager = itemManager;
        this.database = database;
    }

    // ==================== 统一判定入口（按情况决策） ====================

    /**
     * 已上锁箱子交互判定：受保护且关闭撬锁时，保护所有者接管卸锁、非所有者取消全部交互；
     * 启用撬锁（P=true）时不做任何干预（锁保留，可被撬开）。
     */
    public InteractDecision decideInteract(Block block, Player player) {
        if (block == null || !isProtected(block)) {
            return InteractDecision.allow();
        }
        // 启用撬锁（P=true）：保护所有者也不自动卸锁，锁保留，按正常已上锁逻辑处理（钥匙/撬锁）
        if (config.isProtectionPickingEnabled()) {
            return InteractDecision.allow();
        }
        // 关闭撬锁（P=false）：保护所有者接管卸锁（锁返还上锁者，仅提示所有者一条消息）
        if (isOwner(block, player)) {
            return InteractDecision.takeoverUnlock(Messages.CHEST_PROTECTED, Messages.CHEST_PROTECTED_FORMAT);
        }
        // 无所有者保护（如 NoBuildPlus 世界 flag 保护）：没有"保护所有者"可接管卸锁，
        // 放行本插件上锁者本人（可正常交互卸锁），仅阻止其他玩家的操作，避免箱子被锁死
        if (isOwnerlessProtected(block) && isLocker(player, block)) {
            return InteractDecision.allow();
        }
        return InteractDecision.deny(Messages.CHEST_PROTECTED_ACCESS_DENIED, Messages.CHEST_PROTECTED_ACCESS_DENIED_FORMAT);
    }

    /**
     * 上锁判定：受保护箱子上锁限制。关闭撬锁（P=false）时一律禁止上锁；启用撬锁（P=true）时
     * 仅保护所有者可以上锁，避免箱子被其他玩家占用。
     */
    public InteractDecision decideLock(Block block, Player player) {
        if (block == null || !isProtected(block)) {
            return InteractDecision.allow();
        }
        if (config.isProtectionPickingEnabled()) {
            if (isOwner(block, player)) {
                return InteractDecision.allow();
            }
        }
        return InteractDecision.deny(Messages.CHEST_PROTECTED_LOCK_DENIED, Messages.CHEST_PROTECTED_LOCK_DENIED_FORMAT);
    }

    // ==================== 检测统一入口 ====================

    /** 方块是否受任一已装配保护插件保护（单个适配器查询失败只跳过它自己）。 */
    public boolean isProtected(Block block) {
        for (ProtectionAdapter adapter : adapters) {
            try {
                if (adapter.isProtected(block)) {
                    return true;
                }
            } catch (LinkageError | Exception e) {
                logAdapterOpFailure(adapter.pluginType(), "isProtected", e);
            }
        }
        return false;
    }

    /** 获取保护所有者 UUID（按适配器装配顺序取第一个非空；无保护或无所有者概念返回 null）。 */
    public UUID getOwnerUUID(Block block) {
        for (ProtectionAdapter adapter : adapters) {
            try {
                UUID owner = adapter.getOwnerUUID(block);
                if (owner != null) {
                    return owner;
                }
            } catch (LinkageError | Exception e) {
                logAdapterOpFailure(adapter.pluginType(), "getOwnerUUID", e);
            }
        }
        return null;
    }

    /**
     * 是否受"无所有者保护"（如 NoBuildPlus 世界 flag 保护）：此类保护没有所有者概念，
     * 交互权限归本插件上锁者本人（仅阻止非上锁者的操作），避免箱子被锁死无法操作。
     */
    public boolean isOwnerlessProtected(Block block) {
        return isProtected(block) && getOwnerUUID(block) == null;
    }

    /** 玩家是否为保护所有者（需同时具备管理员权限与 protectionOwner 权限，避免误授普通玩家后
     *  可系统性解除任意受保护箱子的本插件锁；普通所有者按保护所有者 UUID 判定）。 */
    public boolean isOwner(Block block, Player player) {
        if (player.hasPermission("chesttheft.admin") && player.hasPermission("chesttheft.protectionOwner")) {
            return true;
        }
        UUID owner = getOwnerUUID(block);
        return owner != null && owner.equals(player.getUniqueId());
    }

    // ==================== 本插件核心操作事件（统一入口调度） ====================

    /** 已上锁箱子交互入口：按 {@link #decideInteract} 决策执行（放行 / 阻止 / 保护所有者接管卸锁）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestInteract(ChestInteractEvent event) {
        InteractDecision decision = decideInteract(event.getBlock(), event.getPlayer());
        switch (decision.getType()) {
            case TAKEOVER_UNLOCK -> {
                event.setCancelled(true);
                unlockAndReturn(event.getBlock(), event.getPlayer());
                decision.sendTo(event.getPlayer());
            }
            case DENY -> {
                event.setCancelled(true);
                decision.sendTo(event.getPlayer());
            }
            default -> {
                // ALLOW：不做干预
            }
        }
    }

    /** 上锁前限制：按 {@link #decideLock} 决策执行（阻止时取消上锁）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestLock(ChestLockEvent event) {
        InteractDecision decision = decideLock(event.getBlock(), event.getPlayer());
        if (decision.getType() == InteractDecision.Type.DENY) {
            event.setCancelled(true);
            decision.sendTo(event.getPlayer());
        }
    }

    /**
     * 打开容器统一入口：各适配器按需事件内临时授予打开权限（先撤销残留授权再判定，修复覆盖授权失效）。
     * 授权在玩家交互事件链内同步完成（早于保护插件 NORMAL 检查），打开动作完成后下一 tick 统一收回，
     * 使授权窗口只有一次事件处理时长，不写库不持久化。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestOpen(ChestOpenEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        // 授权阶段即使抛异常也必须排入收回，否则已授权方会残留到会话结束
        try {
            grantOpenAccess(player, block);
        } finally {
            scheduleRevoke(player, block);
        }
    }

    /**
     * 收回调度：打开动作（含保护插件可能的 InventoryOpenEvent 检查）在当前 tick 内完成后，
     * 下一 tick 统一收回各适配器的临时授权，窗口为零。逐适配器隔离，单个失败不影响其余。
     */
    private void scheduleRevoke(Player player, Block block) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) {
                return; // 插件已禁用：适配器 cleanup 兜底撤销
            }
            forEachAdapter("revokeAccess", adapter -> adapter.revokeAccess(block, player));
        });
    }

    /**
     * 打开容器临时授权统一入口：遍历适配器，各适配器自行判断方块是否在其保护范围
     * 并执行授权。不做 isProtected 粗筛——部分保护无边界可判（如 Towny 野地保护仅能由
     * prepare 依据玩家权限判定，isTownyProtected 对野地返回 false），粗筛会漏掉这些场景。
     */
    private void grantOpenAccess(Player player, Block block) {
        if (block == null) {
            return;
        }
        forEachAdapter("grantOpenAccess", adapter -> adapter.grantOpenAccess(block, player));
    }

    /**
     * 逐适配器执行操作并隔离异常：单个适配器失败只跳过它自己，后续适配器照常执行。
     * 与 {@link #register()} 的隔离级别一致（catch LinkageError | Exception，不吞 OOM 等 Error）。
     */
    private void forEachAdapter(String operation, Consumer<ProtectionAdapter> action) {
        for (ProtectionAdapter adapter : adapters) {
            try {
                action.accept(adapter);
            } catch (LinkageError | Exception e) {
                logAdapterOpFailure(adapter.pluginType(), operation, e);
            }
        }
    }

    // ==================== 装配与注销 ====================

    /**
     * 装配并监听各保护适配器：必须先按插件名判断存在再实例化（适配器引用插件类型，
     * 缺失时 new 会抛 NoClassDefFoundError 导致整个插件启用失败），构造/装配/清理逐个隔离。
     */
    public void register() {
        registerAdapterIfPresent("Bolt", () -> new BoltAdapter(plugin, config, database));
        registerAdapterIfPresent("LWC", () -> new LwcAdapter(plugin, database));
        registerAdapterIfPresent("Residence", () -> new ResidenceAdapter(plugin, database));
        registerAdapterIfPresent("Dominion", () -> new DominionAdapter(plugin, config, database));
        registerAdapterIfPresent("GriefDefender", () -> new GriefDefenderAdapter(plugin, database));
        registerAdapterIfPresent("Towny", () -> new TownyAdapter(plugin, database));
        registerAdapterIfPresent("WorldGuard", () -> new WorldGuardAdapter(plugin, database));
        registerAdapterIfPresent("NoBuildPlus", () -> new NoBuildPlusAdapter(plugin));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (ProtectionAdapter adapter : adapters) {
            // 单个适配器异常只停用该兼容，不影响其它适配器与插件本体
            try {
                adapter.register(this::handleProtectionCreated);
                // 清理上次崩溃残留的临时授权（持久化型保护授权落库，崩溃后不会自动消失）
                adapter.cleanupStale();
            } catch (LinkageError | Exception e) {
                logAdapterFailure(adapter.pluginType(), e);
            }
        }
    }

    /** 插件已安装时才创建并装配适配器；工厂延迟执行，插件缺失时适配器类不会被加载。 */
    private void registerAdapterIfPresent(String pluginName, Supplier<ProtectionAdapter> factory) {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            return;
        }
        ProtectionAdapter adapter;
        try {
            adapter = factory.get();
        } catch (LinkageError | Exception e) {
            logAdapterFailure(pluginName, e);
            return;
        }
        if (adapter.isActive()) {
            adapters.add(adapter);
        }
    }

    /** 记录某个保护适配器初始化失败：只停用该兼容，不抛出。 */
    private void logAdapterFailure(String pluginName, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "[ChestTheft] Protection integration '" + pluginName
                + "' failed to initialise; its compatibility features are disabled", error);
    }

    /** 记录某个适配器的运行期操作失败：只跳过该适配器本次操作，其余适配器与流程不受影响。 */
    private void logAdapterOpFailure(String pluginName, String operation, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "[ChestTheft] Protection adapter '" + pluginName
                + "' failed during '" + operation + "'; this operation was skipped", error);
    }

    /** 注销监听（插件禁用时调用）：清理各适配器的临时授权并撤销订阅。 */
    public void unregister() {
        for (ProtectionAdapter adapter : adapters) {
            try {
                adapter.unregister();
            } catch (LinkageError | Exception e) {
                logAdapterFailure(adapter.pluginType(), e);
            }
        }
        adapters.clear();
    }

    // ==================== 共享：保护创建后自动卸锁 ====================

    /** 核心处理：撬锁关闭且箱子已被本插件上锁时，自动卸下锁并返还、提示上锁者。 */
    private void handleProtectionCreated(Block block) {
        // 插件已禁用（Bolt 回调无取消订阅机制）或撬锁功能启用时不处理
        if (!plugin.isEnabled() || block == null || config.isProtectionPickingEnabled() || !chestService.isLocked(block)) {
            return;
        }
        unlockAndReturn(block, null);
        if (config.isDebug()) {
            plugin.getLogger().info(Messages.getLog(Messages.LOG_PROTECTION_AUTO_UNLOCK, BlockLocation.from(block)));
        }
    }

    /**
     * 卸下锁并返还锁物品：先取上锁者（卸锁会删除数据库记录）再卸锁，返还给在线的上锁者
     * （直接入包，背包满则掉落），不在线才掉落；自动补默认锁 / PDC 标记，避免"假锁"或物品消失。
     * actor 为交互玩家（与上锁者相同时跳过通知，避免重复提示）；null 表示保护创建回调，总是通知上锁者。
     */
    private void unlockAndReturn(Block block, Player actor) {
        String lockerUuid = chestService.getLocker(block);
        UnlockResult unlockResult = chestService.unlock(block);
        if (unlockResult.isError()) {
            // 写库失败：记录仍在，绝不能补发锁物品
            if (actor != null) {
                Messages.send(actor, Messages.LOCK_FAILED, Messages.LOCK_FAILED_FORMAT);
            }
            return;
        }
        ItemStack lockItem = unlockResult.getItem();
        if (lockItem == null || lockItem.getType().isAir()) {
            // 数据库无锁物品数据（旧数据/反序列化失败）时补发默认锁，避免锁物品凭空消失
            lockItem = itemManager.createDefault(ItemType.LOCK, 1);
        } else if (itemManager.getId(lockItem) == null) {
            // 旧数据序列化丢失 PDC：重新标记为锁物品，避免返还后成为无法上锁/使用的"假锁"
            itemManager.tagAsDefault(lockItem, ItemType.LOCK);
        }
        if (lockItem != null) {
            giveLockItem(block, lockerUuid, lockItem);
        }
        // 上锁者与交互玩家相同时已由决策消息提示，不再重复发送
        if (actor == null || lockerUuid == null || !lockerUuid.equals(actor.getUniqueId().toString())) {
            notifyLocker(lockerUuid);
        }
    }

    /**
     * 返还锁物品：上锁者在线时直接放入其背包（背包满则掉落），不在线时才掉落。
     * 直接入包可避免掉落物因拾取范围/位置问题导致"锁消失"，保证返还可靠。
     */
    private void giveLockItem(Block block, String lockerUuid, ItemStack lockItem) {
        Player owner = resolveOnlineLocker(lockerUuid);
        if (owner != null) {
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(lockItem);
            for (ItemStack drop : leftover.values()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), drop);
            }
        } else {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), lockItem);
        }
    }

    /** 解析在线的上锁者；不在线或 UUID 非法时返回 null。 */
    private Player resolveOnlineLocker(String lockerUuid) {
        if (lockerUuid == null || lockerUuid.isEmpty()) {
            return null;
        }
        try {
            Player owner = Bukkit.getPlayer(UUID.fromString(lockerUuid));
            return (owner != null && owner.isOnline()) ? owner : null;
        } catch (IllegalArgumentException e) {
            // 非法 UUID（旧数据）
            return null;
        }
    }

    /** 提示在线的上锁者：锁因箱子受外部保护被自动卸下。 */
    private void notifyLocker(String lockerUuid) {
        Player owner = resolveOnlineLocker(lockerUuid);
        if (owner != null) {
            Messages.send(owner, Messages.CHEST_PROTECTED, Messages.CHEST_PROTECTED_FORMAT);
        }
    }

    /** 判断玩家是否为该箱子的本插件上锁者。 */
    private boolean isLocker(Player player, Block block) {
        String lockerUuid = chestService.getLocker(block);
        return lockerUuid != null && lockerUuid.equals(player.getUniqueId().toString());
    }
}
