package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.LockConfigManager;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChestListener implements Listener {
    private final ChestService chestService;
    private final GameManager gameManager;
    private final ItemManager itemManager;
    private final PluginConfig config;
    /** 不同等级锁的小游戏配置（gamelevel/lock.yml），撬锁时按锁等级取对应配置。 */
    private final LockConfigManager lockConfigManager;
    /** 触发器系统：在撬锁成功/失败/取消、上锁、钥匙开锁/配对时执行配置动作。 */
    private final TriggerManager triggerManager;
    /** 卸锁确认状态：玩家待确认的卸锁目标（位置 + 时间戳），需交互两次才真正卸锁。 */
    private final Map<UUID, UnlockConfirm> pendingUnlock = new HashMap<>();
    /** 卸锁确认窗口（毫秒），超时后需重新发起确认。 */
    private static final long UNLOCK_CONFIRM_TIMEOUT_MS = 5000L;

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
        // 其他插件（如更早注册的 LOWEST 保护插件）已取消本次交互：本插件不处理，避免触发撬锁开始/配对/上锁；
        // 例外：启用撬锁（P=true）时，外部保护对本插件"撬锁尝试 / 已获开箱授权（撬锁成功）"的取消应忽略，
        // 二者的后续流程（ChestOpenEvent）都会完成外部保护的临时授权，拦截取消会阻断流程
        if (event.isCancelled() && !isExternalCancelIgnored(item, event, block, player)) {
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
                // 统一放行：任何已上锁的可上锁方块（箱子/陷阱箱/木门等）均可持撬锁器开始撬锁
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !config.isLockable(block.getType())) {
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
            // 仅右键上锁（左键放行不处理，防误触锁定）
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
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
                Messages.send(player, Messages.BLOCK_LOCKED, Messages.BLOCK_LOCKED_FORMAT);
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
                    // 不可卸锁（功能关闭 / 非上锁者且配置关闭）：放行按普通点击打开箱子
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
            } else {
                // 位置匹配但凭证不匹配：锁已更换，旧钥匙失效
                event.setCancelled(true);
                Messages.send(player, Messages.KEY_CHANGED, Messages.KEY_CHANGED_FORMAT);
            }
            return;
        }
        if (itemManager.getPairedLock(key) != null) {
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
        ItemStack returned = chestService.unlock(block);
        if (returned == null || returned.getType().isAir()) {
            // 无保存的锁物品（如旧数据），返还默认锁物品
            returned = itemManager.createDefault(ItemType.LOCK, 1);
            if (returned == null) {
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
            case FAIL -> {
                Messages.send(player, Messages.FAIL, Messages.FAIL_FORMAT);
                session.getConfig().getFailSound().play(player);
                triggerManager.fire(TriggerType.FAIL, new TriggerContext(player, BlockLocation.from(session.getTarget())));
                fireLockTrigger(session.getTarget(), TriggerType.FAIL, player);
                gameManager.endGame(player);
            }
            // 命中但需继续（多阶段玩法如节奏条）：会话内部已更新命中状态并刷新进度条，不结束会话
            case CONTINUE -> {
            }
        }
    }

    /**
     * 是否应忽略外部保护插件对本次交互的取消：仅当启用撬锁（P=true）且本次属于本插件的
     * 撬锁尝试、已获开箱授权的打开或匹配钥匙打开。外部保护（如 LOWEST 且先于本插件注册的
     * Dominion）会取消未授权玩家对保护箱的交互；P=true 时本插件设计上允许偷窃，故这些操作
     * 不应被阻断，其后续流程会通过 ChestOpenEvent 临时授予外部保护访问权限。
     */
    private boolean isExternalCancelIgnored(ItemStack item, PlayerInteractEvent event, Block block, Player player) {
        if (!config.isProtectionPickingEnabled()) {
            return false;
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
     * 是否为可忽略外部取消的撬锁尝试：启用撬锁（protection.picking-enabled）且持撬锁器右键上锁的可上锁方块
     * （箱子/陷阱箱/木门等，方块已在上层归一化为已上锁的一侧）。
     * 外部保护插件（如 LOWEST 优先级且注册早于本插件的 Bolt）会取消未授权玩家对保护箱的交互，
     * 但撬锁启用时本插件设计上允许偷窃，因此这类取消不应阻止撬锁开始。
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
     * 开始撬锁小游戏。
     *
     * @param target 上锁的箱子方块（已归一化：双箱点击未上锁侧时传上锁侧，
     *               保证成功后授权的开箱位置与实际上锁侧一致，打开时不匹配而失败）
     */
    private void startNewGame(PlayerInteractEvent event, Player player, Block target) {
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
        ItemStack lockItem = chestService.unlock(block);
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
            if (!chestService.isLocked(block)) {
                continue;
            }
            ItemStack lockItem = chestService.unlock(block);
            if (lockItem != null) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), lockItem);
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (gameManager.isPlaying(player)) {
            gameManager.endGame(player);
        }
        // 清理待确认的卸锁目标，避免过期条目长期驻留内存
        pendingUnlock.remove(player.getUniqueId());
    }
}
