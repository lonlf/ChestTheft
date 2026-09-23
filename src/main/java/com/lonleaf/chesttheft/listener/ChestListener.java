package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.database.UnlockResult;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.minigame.MiniGameSession;
import com.lonleaf.chesttheft.event.ChestInteractEvent;
import com.lonleaf.chesttheft.event.ChestKeyOpenEvent;
import com.lonleaf.chesttheft.event.ChestLockEvent;
import com.lonleaf.chesttheft.event.ChestOpenEvent;
import com.lonleaf.chesttheft.event.ChestPickStartEvent;
import com.lonleaf.chesttheft.event.ChestUnlockEvent;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
import com.lonleaf.chesttheft.trigger.TriggerContext;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import com.lonleaf.chesttheft.trigger.TriggerType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;



import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class ChestListener implements Listener {
    private final ChestService chestService;
    private final GameManager gameManager;
    private final ItemManager itemManager;
    private final PluginConfig config;
    /** 不同等级锁的小游戏配置（gamelevel/locklevel.yml），撬锁时按锁等级取对应配置。 */
    private final LockConfigManager lockConfigManager;
    /** 触发器系统：在撬锁成功/失败/取消、上锁、钥匙开锁/配对时执行配置动作。 */
    private final TriggerManager triggerManager;
    /** 卸锁确认状态：玩家待确认的卸锁目标（位置 + 时间戳），需交互两次才真正卸锁。 */
    private final Map<UUID, UnlockConfirm> pendingUnlock = new HashMap<>();
    /** 卸锁确认窗口（毫秒），超时后需重新发起确认。 */
    private static final long UNLOCK_CONFIRM_TIMEOUT_MS = 5000L;
    /** 重新配对确认状态：玩家待确认的重新配对目标（位置 + 时间戳），需交互两次才真正改配。 */
    private final Map<UUID, RepairConfirm> pendingRepair = new HashMap<>();
    /** 重新配对确认窗口（毫秒），超时后需重新发起确认。 */
    private static final long REPAIR_CONFIRM_TIMEOUT_MS = 5000L;

    public ChestListener(ChestService chestService, GameManager gameManager,
                         ItemManager itemManager, PluginConfig config, LockConfigManager lockConfigManager,
                         TriggerManager triggerManager) {
        this.chestService = chestService;
        this.gameManager = gameManager;
        this.itemManager = itemManager;
        this.config = config;
        this.lockConfigManager = lockConfigManager;
        this.triggerManager = triggerManager;
    }

    /**
     * LOWEST 优先级：先于 Bolt 等保护插件运行，使钥匙/撬锁通过的打开在触发
     * ChestOpenEvent 时完成临时授权，Bolt 的 canAccess 检查随即放行。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // 撬锁游戏中：任意左右键点击（方块 / 空气）即进行成功或失败判定，不再限定点击上锁箱子
        if (gameManager.isPlaying(player)) {
            debugLock(player, "click consumed by an active minigame session (isPlaying=true, target="
                    + gameManager.getSession(player).getTarget() + ")");
            handleGameClick(event, player);
            return;
        }

        ItemStack item = event.getItem();
        Block block = event.getClickedBlock();
        if (block != null) {
            // 双箱/门等一体方块：点击未上锁的一侧时归一化到已上锁的一侧处理
            // （双箱共享容器、门上下半一体，未上锁侧可直接打开整个结构，构成安全漏洞）
            Block lockedBlock = resolveLockedBlock(block);
            if (lockedBlock != null) {
                block = lockedBlock;
            }
            // 门已打开（关门动作，仅右键切换门状态）：关门不涉及物品安全（门无容器），
            // 空手/任意手持直接放行，置于外部取消检查之前：即使保护插件已取消交互也放行关门；
            // 左键交互（配对/卸锁）不受影响
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                    && isDoor(block.getType()) && isDoorOpen(block) && chestService.isLocked(block)) {
                // 同样先触发打开事件完成临时授权（下一 tick 收回），避免保护插件拦截关门交互
                if (fireOpenEvent(event, player, block, BlockLocation.from(block))) {
                    event.setCancelled(false);
                }
                return;
            }
        }
        // 外部插件（更早的 LOWEST 保护插件）已取消本次交互时不处理；
        // 例外：P=true 且属于本插件的上锁/撬锁/已授权打开，交由 ChestLockEvent / ChestOpenEvent 统一判定
        if (event.isCancelled() && !isExternalCancelIgnored(item, event, block, player)) {
            // P=false 时虽不绕过外部保护，但被拦截的上锁尝试仍要给反馈，避免毫无响应
            if (isLockAttemptIgnoringCancel(item, event, block)) {
                Messages.send(player, Messages.CHEST_PROTECTED_LOCK_DENIED, Messages.CHEST_PROTECTED_LOCK_DENIED_FORMAT);
            } else if (isKeyOnUnlockedBlock(item, event, block)) {
                Messages.send(player, Messages.KEY_ON_UNLOCKED, Messages.KEY_ON_UNLOCKED_FORMAT);
            }
            debugLock(player, "skipped: event cancelled by another plugin | action=" + event.getAction()
                    + ", block=" + (block == null ? "null" : block.getType())
                    + ", held=" + (item == null ? "null" : item.getType())
                    + ", resolvedType=" + (item == null ? "null" : itemManager.getType(item))
                    + ", itemId=" + (item == null ? "null" : itemManager.getId(item))
                    + ", pickingEnabled=" + config.isProtectionPickingEnabled());
            return;
        }
        if (block == null) {
            return;
        }

        if (chestService.isLocked(block)) {
            BlockLocation location = BlockLocation.from(block);
            // 交互入口事件：外部保护（LWC/Bolt）集成在此决定放行或阻止（非所有者且关闭撬锁时取消全部交互），
            // 保护所有者也可在此接管执行卸锁；取消后本插件不再处理该次交互
            ChestInteractEvent interactEvent = new ChestInteractEvent(player, block, location);
            Bukkit.getPluginManager().callEvent(interactEvent);
            if (interactEvent.isCancelled()) {
                event.setCancelled(true);
                return;
            }
            // 撬锁成功后的全局解锁：有效期内任意玩家可直接打开箱子（左键等仍拦截，避免误破坏方块）
            if (gameManager.isLockPicked(location)) {
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    // 打开容器前同样触发打开事件：解锁期内再次打开也需临时授予 Bolt 权限，避免被拦截
                    if (!fireOpenEvent(event, player, block, location)) {
                        return;
                    }
                    event.setCancelled(false);
                    // 该锁处于被撬开的解锁状态：打开时提示所有打开者
                    Messages.send(player, Messages.PICK_UNLOCKED, Messages.PICK_UNLOCKED_FORMAT);
                    // 一次性解锁在打开后消耗（任意玩家首次打开即重新上锁）；限时解锁不受影响
                    gameManager.consumeOnceAccess(location);
                    // 仅"使用钥匙操作"打开后才撤销解锁状态，实现重新上锁；
                    // 空手/其他方式打开保留解锁窗口
                    if (isKeyOperation(item, player, block, location)) {
                        revokeAccessIfLocker(player, block, location);
                    }
                } else {
                    event.setCancelled(true);
                }
                return;
            }
            ItemType handType = item == null ? null : itemManager.getType(item);
            if (handType == ItemType.PICKER) {
                // 仅右键开始撬锁；左键/其他动作取消事件——左键在 MC 中原生语义是挖掘，挖掉上锁方块会
                // 触发 onBlockBreak 掉落锁物品与内容物，完全绕过撬锁机制
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                    event.setCancelled(true);
                    return;
                }
                // 方块类型不在可上锁列表（配置变更后遗留的锁）：保持原放行行为
                if (!config.isLockable(block.getType())) {
                    return;
                }
                startNewGame(event, player, block);
            } else if (handType == ItemType.KEY) {
                if (isKeyInteractionPress(event, player)) {
                    // 交互按键：未配对钥匙由上锁者配对；已配对钥匙卸下锁
                    handleKeyPress(event, player, block, location, item);
                } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    // 仅右键（开箱动作）路径提示不匹配；左键（非交互按键组合）静默取消，避免误破坏方块
                    if (isKeyMatched(item, location, block)) {
                        ChestKeyOpenEvent keyOpenEvent = new ChestKeyOpenEvent(player, block, location);
                        Bukkit.getPluginManager().callEvent(keyOpenEvent);
                        if (keyOpenEvent.isCancelled()) {
                            event.setCancelled(true);
                            return;
                        }
                        if (!fireOpenEvent(event, player, block, location)) {
                            return;
                        }
                        event.setCancelled(false);
                        triggerManager.fire(TriggerType.KEY_OPEN, new TriggerContext(player, location));
                        fireLockTrigger(block, TriggerType.KEY_OPEN, player);
                        // 箱子所有者打开箱子后撤销他人的撬锁授权，实现"重新上锁"
                        revokeAccessIfLocker(player, block, location);
                    } else if (itemManager.isPairedTo(item, location)) {
                        // 位置匹配但凭证不匹配：锁已更换，旧钥匙失效
                        event.setCancelled(true);
                        Messages.send(player, Messages.KEY_CHANGED, Messages.KEY_CHANGED_FORMAT);
                    } else {
                        event.setCancelled(true);
                        Messages.send(player, Messages.KEY_NOT_MATCHED, Messages.KEY_NOT_MATCHED_FORMAT);
                    }
                } else {
                    // 左键（非交互按键组合）：不提示不匹配，仅取消防止破坏方块
                    event.setCancelled(true);
                }
            } else if (!config.isKeyRequireInHand() && hasMatchedBagKey(player, block, location)) {
                ChestKeyOpenEvent keyOpenEvent = new ChestKeyOpenEvent(player, block, location);
                Bukkit.getPluginManager().callEvent(keyOpenEvent);
                if (keyOpenEvent.isCancelled()) {
                    event.setCancelled(true);
                    return;
                }
                if (!fireOpenEvent(event, player, block, location)) {
                    return;
                }
                event.setCancelled(false);
                triggerManager.fire(TriggerType.KEY_OPEN, new TriggerContext(player, location));
                fireLockTrigger(block, TriggerType.KEY_OPEN, player);
                // 箱子所有者打开箱子后撤销他人的撬锁授权，实现"重新上锁"
                revokeAccessIfLocker(player, block, location);
            } else {
                event.setCancelled(true);
                Messages.send(player, Messages.BLOCK_LOCKED, Messages.BLOCK_LOCKED_FORMAT);
            }
        } else if (item != null && itemManager.isType(item, ItemType.LOCK)) {
            // 仅左键上锁（点击事件取消，防止破坏方块）
            if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
                debugLock(player, "holding a LOCK but action=" + event.getAction()
                        + " (expected LEFT_CLICK_BLOCK), interaction ignored");
                return;
            }
            // 仅允许上锁配置列表中的方块类型（lock.lockable-blocks）
            if (!config.isLockable(block.getType())) {
                event.setCancelled(true);
                Messages.send(player, Messages.LOCK_NOT_LOCKABLE, Messages.LOCK_NOT_LOCKABLE_FORMAT);
                return;
            }
            // 上锁事件：外部保护（LWC/Bolt）集成在此阻止对受保护箱子上锁（关闭撬锁时）
            ChestLockEvent lockEvent = new ChestLockEvent(player, block, BlockLocation.from(block), item);
            Bukkit.getPluginManager().callEvent(lockEvent);
            if (lockEvent.isCancelled()) {
                debugLock(player, "blocked: ChestLockEvent cancelled by another listener (block="
                        + block.getType() + " at " + BlockLocation.from(block) + ")");
                event.setCancelled(true);
                return;
            }
            int level = itemManager.getLevel(item);
            // 触发器只存 id 列表（引用形式 id / 内嵌定义临时 id），动作由服务端注册表解析，随物品持久化到数据库
            List<String> triggerIds = itemManager.resolveLockTriggerIds(item);
            if (triggerIds != null) {
                YamlConfiguration tmp = new YamlConfiguration();
                tmp.set("triggers", triggerIds);
                itemManager.setLockTrigger(item, tmp.saveToString());
            }
            // 数据库只存本次消耗的单个锁物品（数量 1），避免卸锁返还时数量错乱；clone 防止后续消耗修改影响已存数据
            ItemStack storedLock = item.clone();
            storedLock.setAmount(1);
            // 上锁失败（并发下位置已存在锁记录）时不消耗锁物品，避免物品凭空消失
            if (!chestService.lock(block, storedLock, player.getUniqueId().toString(), level)) {
                event.setCancelled(true);
                // 区分"位置已被占用"（此刻能查到记录）与数据库写入异常（查不到记录）
                if (chestService.isLocked(block)) {
                    Messages.send(player, Messages.BLOCK_LOCKED, Messages.BLOCK_LOCKED_FORMAT);
                } else {
                    Messages.send(player, Messages.LOCK_FAILED, Messages.LOCK_FAILED_FORMAT);
                }
                return;
            }
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(event.getHand(), item);
            } else {
                player.getInventory().setItem(event.getHand(), null);
            }
            event.setCancelled(true);
            Messages.send(player, Messages.LOCKED_IT, Messages.LOCKED_IT_FORMAT);
            BlockLocation location = BlockLocation.from(block);
            triggerManager.fire(TriggerType.LOCK, new TriggerContext(player, location));
            triggerManager.fireForLock(triggerIds, TriggerType.LOCK, new TriggerContext(player, location));
            // give-key 配置：上锁完成自动给予已配对此锁的钥匙（指定 ID 的钥匙物品）
            giveKeyOnLock(player, block, item, location);
            // 锁等级未配置（撬锁时会降级回退）时恒输出日志便于排查配置问题；等级已配置时仅 debug 输出
            if (!lockConfigManager.isLevelConfigured(level) || config.isDebug()) {
                gameManager.getPlugin().getLogger().info(Messages.getLog(Messages.LOG_LOCK_APPLIED,
                        player.getName(), location, level));
            }
        } else if (isKeyOnUnlockedBlock(item, event, block)) {
            // 手持钥匙左键点未上锁方块（以为钥匙能上锁）：给出指引，否则表现为"点击没反应"
            Messages.send(player, Messages.KEY_ON_UNLOCKED, Messages.KEY_ON_UNLOCKED_FORMAT);
        } else if (config.isDebug() && item != null && !item.getType().isAir()) {
            // debug：未匹配任何分支时输出识别结果（动作/手/物品）
            debugLock(player, "no branch matched: action=" + event.getAction() + ", hand=" + event.getHand()
                    + ", block=" + block.getType() + ", heldItem=" + item.getType()
                    + ", resolvedType=" + itemManager.getType(item) + ", itemId=" + itemManager.getId(item));
        }
    }

    /** debug 模式下输出上锁判定结果，便于定位上不了锁的卡点。 */
    private void debugLock(Player player, String detail) {
        if (config.isDebug()) {
            gameManager.getPlugin().getLogger().info("[ChestTheft][DEBUG] lock: " + detail
                    + " | player=" + player.getName());
        }
    }

    /** give-key 配置：按锁物品定义的 give-key 创建配对钥匙并给予上锁者（未配置或定义无效时不给予）。 */
    private void giveKeyOnLock(Player player, Block block, ItemStack lockItem, BlockLocation location) {
        String keyId = itemManager.getGiveKey(lockItem);
        if (keyId == null || keyId.isEmpty()) {
            return;
        }
        ItemStack key = itemManager.create(keyId, 1);
        if (key == null) {
            gameManager.getPlugin().getLogger().warning(Messages.getLog(Messages.LOG_ITEM_GIVE_KEY_INVALID,
                    keyId, location));
            return;
        }
        if (!itemManager.isType(key, ItemType.KEY)) {
            gameManager.getPlugin().getLogger().warning(Messages.getLog(Messages.LOG_ITEM_GIVE_KEY_INVALID,
                    keyId, location));
            return;
        }
        // 配对到刚上锁的锁（凭证一致，可直接开锁）
        itemManager.setPairedLock(key, location, chestService.getLockToken(block));
        chestService.increasePairedCount(block);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(key);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), drop);
        }
    }

    /** 若玩家为箱子所有者，撤销他人的开箱授权（实现"重新上锁"）。 */
    private void revokeAccessIfLocker(Player player, Block block, BlockLocation location) {
        String locker = chestService.getLocker(block);
        if (locker != null && locker.equals(player.getUniqueId().toString())) {
            gameManager.revokeAllAccess(location);
        }
    }

    /** 触发目标锁物品自带的指定类型触发器；锁未配置对应触发器时不处理。 */
    private void fireLockTrigger(Block block, TriggerType type, Player player) {
        if (block == null) {
            return;
        }
        ItemStack lockItem = chestService.getLockItem(block);
        if (lockItem == null) {
            return;
        }
        List<String> triggerIds = triggerManager.parseTriggerIds(itemManager.getLockTrigger(lockItem));
        if (triggerIds != null) {
            triggerManager.fireForLock(triggerIds, type, new TriggerContext(player, BlockLocation.from(block)));
        }
    }

    /** 是否为钥匙交互按键组合（潜行 + 配置动作），钥匙配对与卸锁共用此按键。 */
    private boolean isKeyInteractionPress(PlayerInteractEvent event, Player player) {
        return event.getAction() == config.getKeyInteractionAction()
                && player.isSneaking() == config.isKeyInteractionSneak();
    }

    /** 交互按键处理：已配对钥匙需两次交互确认卸锁，未配对钥匙由锁主配对，配对其他锁的钥匙不可用。 */
    private void handleKeyPress(PlayerInteractEvent event, Player player, Block block,
                                BlockLocation location, ItemStack key) {
        if (itemManager.isPairedTo(key, location)) {
            if (isKeyMatched(key, location, block)) {
                // 是否允许卸锁：功能开启且（本人为上锁者，或配置允许持钥匙非上锁者卸锁）
                String locker = chestService.getLocker(block);
                boolean isLocker = locker != null && locker.equals(player.getUniqueId().toString());
                if (config.isKeyUnlockEnabled() && (isLocker || config.isKeyUnlockByHolder())) {
                    event.setCancelled(true);
                    // 卸锁需两次交互确认：第一次进入待确认，第二次（同一锁、未超时）才真正卸下
                    long now = System.currentTimeMillis();
                    UnlockConfirm pending = pendingUnlock.get(player.getUniqueId());
                    if (pending != null && pending.location.equals(location)
                            && now - pending.time <= UNLOCK_CONFIRM_TIMEOUT_MS) {
                        pendingUnlock.remove(player.getUniqueId());
                        unlockChest(event, player, block, true);
                    } else {
                        pendingUnlock.put(player.getUniqueId(), new UnlockConfirm(location, now));
                        Messages.send(player, Messages.UNLOCK_CONFIRM, Messages.UNLOCK_CONFIRM_FORMAT);
                    }
                } else {
                    // 不可卸锁（功能关闭 / 非上锁者且配置关闭）：右键退化为正常开箱（不取消）。
                    // 左键交互（默认潜行+左键）必须取消——左键原生语义是挖掘，不取消会挖掉上锁方块
                    // （onBlockBreak 掉落锁物品与内容物），绕过锁与撬锁机制；开箱请用右键
                    if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                        event.setCancelled(true);
                        return;
                    }
                    ChestKeyOpenEvent keyOpenEvent = new ChestKeyOpenEvent(player, block, location);
                    Bukkit.getPluginManager().callEvent(keyOpenEvent);
                    if (keyOpenEvent.isCancelled()) {
                        event.setCancelled(true);
                        return;
                    }
                    if (!fireOpenEvent(event, player, block, location)) {
                        return;
                    }
                    triggerManager.fire(TriggerType.KEY_OPEN, new TriggerContext(player, location));
                }
            } else if (config.isKeyRepairEnabled()) {
                // 锁已更换且允许重新配对：进入重新配对确认（两次交互确认后改配到当前锁）
                handleRepairConfirm(event, player, block, location, key);
            } else {
                // 位置匹配但凭证不匹配：锁已更换，旧钥匙失效
                event.setCancelled(true);
                Messages.send(player, Messages.KEY_CHANGED, Messages.KEY_CHANGED_FORMAT);
            }
            return;
        }
        if (itemManager.getPairedLock(key) != null) {
            if (config.isKeyRepairEnabled()) {
                // 允许重新配对：已配对钥匙改配到当前锁（两次交互确认）
                handleRepairConfirm(event, player, block, location, key);
                return;
            }
            event.setCancelled(true);
            Messages.send(player, Messages.KEY_NOT_MATCHED, Messages.KEY_NOT_MATCHED_FORMAT);
            return;
        }
        // 未配对钥匙：需启用配对功能；配对仅允许上锁者（防止复制钥匙后卸锁偷锁）
        if (!config.isKeyPairEnabled()) {
            event.setCancelled(true);
            Messages.send(player, Messages.KEY_NOT_MATCHED, Messages.KEY_NOT_MATCHED_FORMAT);
            return;
        }
        event.setCancelled(true);
        String locker = chestService.getLocker(block);
        boolean isLocker = locker != null && locker.equals(player.getUniqueId().toString());
        if (!isLocker) {
            Messages.send(player, Messages.PAIR_NOT_LOCKER, Messages.PAIR_NOT_LOCKER_FORMAT);
            return;
        }
        itemManager.setPairedLock(key, location, chestService.getLockToken(block));
        chestService.increasePairedCount(block);
        // event.getItem() 是手持物品的快照副本，修改其 PDC 不会写回玩家的实际物品；
        // 必须重新写回对应手部槽位，配对数据才会持久化，并触发 SET_SLOT 包使客户端立即刷新 lore。
        player.getInventory().setItem(event.getHand(), key);
        Messages.send(player, Messages.PAIR_SUCCESS, Messages.PAIR_SUCCESS_FORMAT);
        triggerManager.fire(TriggerType.KEY_PAIR, new TriggerContext(player, location));
    }

    /** 重新配对处理：已配对钥匙改配到当前锁，需两次交互确认；仅上锁者可重新配对（防止复制钥匙后改配偷锁）。 */
    private void handleRepairConfirm(PlayerInteractEvent event, Player player, Block block,
                                     BlockLocation location, ItemStack key) {
        event.setCancelled(true);
        String locker = chestService.getLocker(block);
        boolean isLocker = locker != null && locker.equals(player.getUniqueId().toString());
        if (!isLocker) {
            Messages.send(player, Messages.PAIR_NOT_LOCKER, Messages.PAIR_NOT_LOCKER_FORMAT);
            return;
        }
        // 重新配对需两次交互确认：第一次进入待确认，第二次（同一锁、未超时）才真正改配
        long now = System.currentTimeMillis();
        RepairConfirm pending = pendingRepair.get(player.getUniqueId());
        if (pending != null && pending.location.equals(location)
                && now - pending.time <= REPAIR_CONFIRM_TIMEOUT_MS) {
            pendingRepair.remove(player.getUniqueId());
            itemManager.setPairedLock(key, location, chestService.getLockToken(block));
            chestService.increasePairedCount(block);
            // 事件物品是快照副本，重新写回手部槽位持久化并刷新 lore
            player.getInventory().setItem(event.getHand(), key);
            Messages.send(player, Messages.REPAIR_SUCCESS, Messages.REPAIR_SUCCESS_FORMAT);
            triggerManager.fire(TriggerType.KEY_PAIR, new TriggerContext(player, location));
        } else {
            pendingRepair.put(player.getUniqueId(), new RepairConfirm(location, now));
            Messages.send(player, Messages.REPAIR_CONFIRM, Messages.REPAIR_CONFIRM_FORMAT);
        }
    }

    /** 钥匙与锁完全匹配：配对位置一致且配对凭证与锁当前凭证一致。 */
    private boolean isKeyMatched(ItemStack key, BlockLocation location, Block block) {
        if (!itemManager.isPairedTo(key, location)) {
            return false;
        }
        String token = itemManager.getPairedToken(key);
        String current = chestService.getLockToken(block);
        return token != null && token.equals(current);
    }

    /**
     * 触发打开容器事件（外部保护在此临时授权，避免打开瞬间被拦截）；事件被取消返回 false。
     */
    private boolean fireOpenEvent(PlayerInteractEvent event, Player player, Block block, BlockLocation location) {
        ChestOpenEvent openEvent = new ChestOpenEvent(player, block, location);
        Bukkit.getPluginManager().callEvent(openEvent);
        if (openEvent.isCancelled()) {
            event.setCancelled(true);
            return false;
        }
        return true;
    }

    /** 卸下锁并返还锁物品；sendUnlockMessage 为 false 时（保护所有者自动卸锁）不重复发送卸锁成功提示。 */
    private void unlockChest(PlayerInteractEvent event, Player player, Block block, boolean sendUnlockMessage) {
        // 卸锁事件：外部保护集成等可在此阻止卸锁
        ChestUnlockEvent unlockEvent = new ChestUnlockEvent(player, block, BlockLocation.from(block));
        Bukkit.getPluginManager().callEvent(unlockEvent);
        if (unlockEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        UnlockResult unlockResult = chestService.unlock(block);
        if (unlockResult.isError()) {
            // 写库失败：记录仍在，补发锁物品等于白送一把锁
            Messages.send(player, Messages.LOCK_FAILED, Messages.LOCK_FAILED_FORMAT);
            return;
        }
        ItemStack returned = unlockResult.getItem();
        if (returned == null || returned.getType().isAir()) {
            // 无保存的锁物品（如旧数据），返还默认锁物品
            returned = itemManager.createDefault(ItemType.LOCK, 1);
            if (returned == null) {
                gameManager.getPlugin().getLogger().severe(Messages.getLog(Messages.LOG_LOCK_RETURN_ITEM_FAIL, block.getLocation()));
                return;
            }
        } else if (itemManager.getId(returned) == null) {
            // 序列化可能丢失 PDC 标签，重新标记确保仍为锁物品
            itemManager.tagAsDefault(returned, ItemType.LOCK);
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(returned);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), drop);
        }
        // 卸锁后手中钥匙的配对已失效，恢复为未配对状态（事件物品为快照，需写回手部槽位持久化并刷新 lore）
        ItemStack hand = event.getItem();
        if (hand != null && itemManager.isType(hand, ItemType.KEY)) {
            itemManager.clearPairedLock(hand);
            player.getInventory().setItem(event.getHand(), hand);
        }
        if (sendUnlockMessage) {
            Messages.send(player, Messages.UNLOCK_SUCCESS, Messages.UNLOCK_SUCCESS_FORMAT);
        }
    }

    /** 卸锁待确认状态：记录目标锁位置与发起时间。 */
    private static final class UnlockConfirm {
        final BlockLocation location;
        final long time;

        UnlockConfirm(BlockLocation location, long time) {
            this.location = location;
            this.time = time;
        }
    }

    /** 重新配对待确认状态：记录目标锁位置与发起时间。 */
    private static final class RepairConfirm {
        final BlockLocation location;
        final long time;

        RepairConfirm(BlockLocation location, long time) {
            this.location = location;
            this.time = time;
        }
    }

    /** 背包中是否有与指定锁完全匹配的钥匙；仅当配置允许背包取用时生效（require-in-hand: false）。 */
    private boolean hasMatchedBagKey(Player player, Block block, BlockLocation location) {
        if (config.isKeyRequireInHand()) {
            return false;
        }
        for (ItemStack content : player.getInventory().getContents()) {
            if (content != null && itemManager.isType(content, ItemType.KEY)
                    && isKeyMatched(content, location, block)) {
                return true;
            }
        }
        return false;
    }

    /** 本次打开是否为"钥匙操作"：手持配对钥匙，或未要求手持时背包中存在配对钥匙。 */
    private boolean isKeyOperation(ItemStack item, Player player, Block block, BlockLocation location) {
        if (item != null && itemManager.isType(item, ItemType.KEY)) {
            return isKeyMatched(item, location, block);
        }
        return hasMatchedBagKey(player, block, location);
    }

    private void handleGameClick(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        MiniGameSession session = gameManager.getSession(player);
        if (session == null) {
            return;
        }

        switch (session.onClick()) {
            case SUCCESS -> gameManager.successGame(session);
            // 失败统一走 GameManager.failGame（消息/音效/触发器 + 异常安全 + 幂等），避免此处再维护一份结算逻辑
            case FAIL -> gameManager.failGame(session);
            // 命中但需继续（多阶段玩法如节奏条）：会话内部已更新命中状态并刷新进度条，不结束会话
            case CONTINUE -> {
            }
        }
    }

    /**
     * 是否忽略外部保护插件对本次交互的取消：仅 P=true 时豁免本插件的上锁/撬锁/已授权打开，
     * 后续由 ChestLockEvent / ChestOpenEvent 统一判定并完成临时授权。
     */
    private boolean isExternalCancelIgnored(ItemStack item, PlayerInteractEvent event, Block block, Player player) {
        if (!config.isProtectionPickingEnabled()) {
            return false;
        }
        // 上锁尝试：手持锁左键点未上锁的可上锁方块。
        // 必须在此豁免：外部保护会取消未授权玩家的左键，而能否上锁应由 decideLock 判定
        if (isLockAttemptIgnoringCancel(item, event, block)) {
            return true;
        }
        // 撬锁尝试：持撬锁器右键上锁箱子（开始撬锁流程）
        if (isPickAttemptIgnoringCancel(item, event, block)) {
            return true;
        }
        if (block == null || !chestService.isLocked(block)) {
            return false;
        }
        BlockLocation location = BlockLocation.from(block);
        // 已获全局解锁（撬锁成功后）：放行打开流程（ChestOpenEvent 临时授予外部保护访问权限）
        if (gameManager.isLockPicked(location)) {
            return true;
        }
        // 匹配钥匙打开：非上锁者持已配对且凭证匹配的钥匙（或配置允许时背包中的匹配钥匙），放行打开流程
        boolean handKeyMatched = item != null && itemManager.isType(item, ItemType.KEY)
                && isKeyMatched(item, location, block);
        boolean bagKeyMatched = !config.isKeyRequireInHand() && hasMatchedBagKey(player, block, location);
        return event.getAction() == Action.RIGHT_CLICK_BLOCK && (handKeyMatched || bagKeyMatched);
    }

    /**
     * 是否为可忽略外部取消的撬锁尝试：启用撬锁（protection.picking-enabled）且持撬锁器右键
     * 上锁的可上锁方块（箱子/陷阱箱/木门等，已在上层归一化为已上锁的一侧）。
     * 外部保护会取消未授权玩家的交互，但撬锁启用时本插件设计上允许偷窃，此类取消不应阻止撬锁开始。
     */
    private boolean isPickAttemptIgnoringCancel(ItemStack item, PlayerInteractEvent event, Block block) {
        return config.isProtectionPickingEnabled()
                && item != null
                && itemManager.getType(item) == ItemType.PICKER
                && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && block != null
                && config.isLockable(block.getType())
                && chestService.isLocked(block);
    }

    /**
     * 是否为可忽略外部取消的上锁尝试（手持锁左键未上锁方块）：能否上锁仍由 decideLock 判定，
     * 保护所有者可上锁、非所有者收到本插件提示，因此不绕过保护。
     */
    private boolean isLockAttemptIgnoringCancel(ItemStack item, PlayerInteractEvent event, Block block) {
        return item != null
                && itemManager.getType(item) == ItemType.LOCK
                && event.getAction() == Action.LEFT_CLICK_BLOCK
                && block != null
                && config.isLockable(block.getType())
                && !chestService.isLocked(block);
    }

    /** 手持钥匙左键点未上锁的可上锁方块（限定可上锁方块，避免拿钥匙点普通方块也提示）。 */
    private boolean isKeyOnUnlockedBlock(ItemStack item, PlayerInteractEvent event, Block block) {
        return item != null
                && itemManager.getType(item) == ItemType.KEY
                && event.getAction() == Action.LEFT_CLICK_BLOCK
                && block != null
                && config.isLockable(block.getType())
                && !chestService.isLocked(block);
    }

    /**
     * 开始撬锁小游戏；target 为已归一化的上锁侧方块（双箱点击未上锁侧时传上锁侧），
     * 保证撬锁成功后授权的开箱位置与实际上锁侧一致。
     */
    private void startNewGame(PlayerInteractEvent event, Player player, Block target) {
        // chesttheft.use：允许服务器收回撬锁（偷窃）能力，不影响上锁/钥匙/开箱
        if (!player.hasPermission("chesttheft.use")) {
            Messages.send(player, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return;
        }
        // 撬锁开始事件：外部保护集成等可在此阻止开始撬锁
        ChestPickStartEvent pickEvent = new ChestPickStartEvent(player, target, BlockLocation.from(target));
        Bukkit.getPluginManager().callEvent(pickEvent);
        if (pickEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        // 生效等级 = 锁等级 - 撬锁器等级；小于等于 0 时使用默认配置（getGameConfig 内部处理）
        int lockLevel = chestService.getLockLevel(target);
        int pickerLevel = itemManager.getLevel(event.getItem());
        int effectiveLevel = lockLevel - pickerLevel;
        GameConfig levelConfig = lockConfigManager.getGameConfig(effectiveLevel);
        // 开始提示与规则由小游戏会话 start() 统一显示（先规则后进度条），
        // 此处不再发 START_PICKING，避免同通道标题后发覆盖规则消息。
        if (gameManager.startGame(player, target, levelConfig) && config.isDebug()) {
            gameManager.getPlugin().getLogger().info(Messages.getLog(Messages.LOG_PICKLOCK_START_DEBUG,
                    player.getName(), BlockLocation.from(target), lockLevel, pickerLevel, effectiveLevel));
        }
    }

    /** MONITOR 最后执行：等 WorldGuard 等保护插件在 HIGHEST 取消后，仅在事件最终未被取消时才掉落锁物品。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        // 事件已被其他插件取消（如领地/权限插件拦截）：不得掉落锁物品或删除数据库记录
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        // 仅处理可上锁方块类型（内存判断，避免对所有被破坏方块做数据库查询）
        if (!config.isLockable(block.getType())) {
            return;
        }
        // 双箱/门等一体方块：破坏未上锁的一侧时同样归一化到已上锁的一侧（破坏任何一侧都会摧毁整个结构）
        Block lockedBlock = resolveLockedBlock(block);
        if (lockedBlock == null) {
            return;
        }
        block = lockedBlock;
        // 方块被破坏时掉落锁物品、清除数据库记录并撤销已授予的开箱授权（防授权残留）
        UnlockResult unlockResult = chestService.unlock(block);
        ItemStack lockItem = unlockResult.isDeleted() ? unlockResult.getItem() : null;
        if (lockItem != null) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), lockItem);
            gameManager.revokeAllAccess(BlockLocation.from(block));
        }
    }

    /**
     * 一体方块归一化：方块本身已上锁时返回自身；方块未上锁但"另一半"已上锁时返回已上锁的一侧；
     * 都不是上锁方块时返回 null。双箱共享同一容器、门上下半为一体，未上锁侧可直接打开/破坏整个
     * 结构，构成安全漏洞，因此交互与破坏需统一按已上锁的一侧处理。
     */
    private Block resolveLockedBlock(Block block) {
        if (chestService.isLocked(block)) {
            return block;
        }
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            if (!(block.getState() instanceof Chest chestState)) {
                return null;
            }
            InventoryHolder holder = chestState.getInventory().getHolder();
            if (!(holder instanceof DoubleChest doubleChest)) {
                return null;
            }
            for (InventoryHolder side : new InventoryHolder[]{doubleChest.getLeftSide(), doubleChest.getRightSide()}) {
                if (side instanceof Chest sideChest) {
                    Block sideBlock = sideChest.getBlock();
                    if (sideBlock != null && chestService.isLocked(sideBlock)) {
                        return sideBlock;
                    }
                }
            }
            return null;
        }
        // 门（上下半一体）：对半已上锁时返回已上锁的一侧
        Block other = doorCounterpart(block);
        if (other != null && chestService.isLocked(other)) {
            return other;
        }
        return null;
    }

    /** 门的另一半（上下半）；非门方块或另一半缺失时返回 null。 */
    private Block doorCounterpart(Block block) {
        if (!isDoor(block.getType())) {
            return null;
        }
        Block up = block.getRelative(BlockFace.UP);
        Block down = block.getRelative(BlockFace.DOWN);
        if (up.getType() == block.getType()) {
            return up;
        }
        if (down.getType() == block.getType()) {
            return down;
        }
        return null;
    }

    /** 是否为门方块（木门/铁门等上下半一体的 *_DOOR；陷阱门 *_TRAPDOOR 不在此列）。 */
    private boolean isDoor(Material material) {
        return material != null && material.name().endsWith("_DOOR") && !material.name().endsWith("_TRAPDOOR");
    }

    /** 门当前是否处于打开状态（门 open 属性；非门方块返回 false）。 */
    private boolean isDoorOpen(Block block) {
        return block.getState().getBlockData() instanceof org.bukkit.block.data.type.Door door && door.isOpen();
    }

    /** MONITOR 最后执行：保护插件取消爆炸后不再掉落锁物品（否则箱子没坏但锁没了）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        dropLockFromExplodedBlocks(event.blockList());
    }

    /** MONITOR 最后执行：保护插件取消爆炸后不再掉落锁物品。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        dropLockFromExplodedBlocks(event.blockList());
    }

    /** 遍历爆炸破坏的方块列表，对上锁的可上锁方块掉落锁物品并清除数据库记录。 */
    private void dropLockFromExplodedBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            // 仅处理可上锁方块类型（内存判断，避免对所有被破坏方块做数据库查询）
            if (!config.isLockable(block.getType())) {
                continue;
            }
            // 双箱/门等一体方块：爆炸破坏未上锁的一侧时归一化到已上锁的一侧，
            // 避免数据库记录悬挂与锁物品丢失（与 onBlockBreak 一致）
            Block lockedBlock = resolveLockedBlock(block);
            if (lockedBlock == null) {
                continue;
            }
            UnlockResult unlockResult = chestService.unlock(lockedBlock);
            ItemStack lockItem = unlockResult.isDeleted() ? unlockResult.getItem() : null;
            if (lockItem != null) {
                lockedBlock.getWorld().dropItemNaturally(lockedBlock.getLocation().add(0.5, 0.5, 0.5), lockItem);
                // 撤销该锁的全局解锁状态，防授权残留（与 onBlockBreak 一致）
                gameManager.revokeAllAccess(BlockLocation.from(lockedBlock));
            }
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && gameManager.isPlaying(event.getPlayer())) {
            MiniGameSession session = gameManager.getSession(event.getPlayer());
            if (session != null) {
                triggerManager.fire(TriggerType.CANCEL,
                        new TriggerContext(event.getPlayer(), BlockLocation.from(session.getTarget())));
                fireLockTrigger(session.getTarget(), TriggerType.CANCEL, event.getPlayer());
            }
            gameManager.endGame(event.getPlayer());
            Messages.send(event.getPlayer(), Messages.CANCEL_PICKING, Messages.CANCEL_PICKING_FORMAT);
        }
    }

    /**
     * 锁被撬开后所有者关闭箱子：撤销全局解锁立即重新上锁。
     * 配置 lock.owner-close-relock 控制（默认开启）；一次性解锁已消耗（重新上锁）时无操作。
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!config.isLockOwnerCloseRelock()) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Block block = inventoryBlock(event.getInventory());
        if (block == null) {
            return;
        }
        // 双箱：关闭任意一侧都归一化到已上锁的一侧
        Block lockedBlock = resolveLockedBlock(block);
        if (lockedBlock == null) {
            return;
        }
        // 仅当该锁处于被撬开的全局解锁状态时处理
        BlockLocation location = BlockLocation.from(lockedBlock);
        if (!gameManager.isLockPicked(location)) {
            return;
        }
        // 仅所有者关闭时立即重新上锁
        String locker = chestService.getLocker(lockedBlock);
        if (locker == null || !locker.equals(player.getUniqueId().toString())) {
            return;
        }
        gameManager.revokeAllAccess(location);
        if (config.isDebug()) {
            gameManager.getPlugin().getLogger().info(Messages.getLog(
                    Messages.LOG_OWNER_CLOSE_RELOCK, player.getName(), location));
        }
    }

    /** 从容器 Inventory 获取其对应的方块；非方块容器（背包/工作台等）返回 null。 */
    private Block inventoryBlock(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        // 单格方块容器（箱子/陷阱箱/木桶/潜影盒等）
        if (holder instanceof org.bukkit.block.BlockState blockState) {
            return blockState.getBlock();
        }
        // 双箱：左右任一侧均可
        if (holder instanceof DoubleChest doubleChest) {
            for (InventoryHolder side : new InventoryHolder[]{doubleChest.getLeftSide(), doubleChest.getRightSide()}) {
                if (side instanceof org.bukkit.block.BlockState sideState) {
                    return sideState.getBlock();
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (gameManager.isPlaying(player)) {
            gameManager.endGame(player);
        }
        // 清理待确认的卸锁/重新配对目标，避免过期条目长期驻留内存
        pendingUnlock.remove(player.getUniqueId());
        pendingRepair.remove(player.getUniqueId());
    }
}
