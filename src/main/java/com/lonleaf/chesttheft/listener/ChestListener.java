package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.minigame.GameSession;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
import com.lonleaf.chesttheft.trigger.TriggerContext;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import com.lonleaf.chesttheft.trigger.TriggerType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChestListener implements Listener {
    private final ChestService chestService;
    private final GameManager gameManager;
    private final ItemManager itemManager;
    private final PluginConfig config;
    /** 不同等级锁的小游戏配置（locklevel/lock.yml），撬锁时按锁等级取对应配置。 */
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

    @EventHandler
    public void onChestClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // 撬锁游戏中：任意左右键点击（方块 / 空气）即进行成功或失败判定，不再限定点击上锁箱子
        if (gameManager.isPlaying(player)) {
            handleGameClick(event, player);
            return;
        }

        ItemStack item = event.getItem();
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        if (chestService.isLocked(block)) {
            BlockLocation location = BlockLocation.from(block);
            // 撬锁成功后的授权：有效期内该玩家可直接打开箱子（左键等仍拦截，避免误破坏方块）
            if (gameManager.hasAccess(player, location)) {
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    event.setCancelled(false);
                    // 一次性授权在打开后消耗；限时授权不受影响
                    gameManager.consumeOnceAccess(player, location);
                } else {
                    event.setCancelled(true);
                }
                return;
            }
            ItemType handType = item == null ? null : itemManager.getType(item);
            if (handType == ItemType.PICKER) {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block.getType() != Material.CHEST) {
                    return;
                }
                startNewGame(event, player);
            } else if (handType == ItemType.KEY) {
                if (isKeyInteractionPress(event, player)) {
                    // 交互按键：未配对钥匙由上锁者配对；已配对钥匙卸下锁
                    handleKeyPress(event, player, block, location, item);
                } else if (isKeyMatched(item, location, block)) {
                    event.setCancelled(false);
                    triggerManager.fire(TriggerType.KEY_OPEN, new TriggerContext(player, location));
                    fireLockTrigger(block, TriggerType.KEY_OPEN, player);
                } else if (itemManager.isPairedTo(item, location)) {
                    // 位置匹配但凭证不匹配：锁已更换，旧钥匙失效
                    event.setCancelled(true);
                    Messages.send(player, Messages.KEY_CHANGED, Messages.KEY_CHANGED_FORMAT);
                } else {
                    event.setCancelled(true);
                    Messages.send(player, Messages.KEY_NOT_MATCHED, Messages.KEY_NOT_MATCHED_FORMAT);
                }
            } else if (!config.isKeyRequireInHand() && hasMatchedBagKey(player, block, location)) {
                event.setCancelled(false);
                triggerManager.fire(TriggerType.KEY_OPEN, new TriggerContext(player, location));
                fireLockTrigger(block, TriggerType.KEY_OPEN, player);
            } else {
                event.setCancelled(true);
                Messages.send(player, Messages.CHEST_LOCKED, Messages.CHEST_LOCKED_FORMAT);
            }
        } else if (item != null && itemManager.isType(item, ItemType.LOCK)) {
            int level = itemManager.getLevel(item);
            // 锁物品定义配置了 triggers 时写入物品标签，随物品持久化到数据库
            String lockTrigger = itemManager.serializeLockTriggers(item);
            if (lockTrigger != null) {
                itemManager.setLockTrigger(item, lockTrigger);
            }
            chestService.lock(block, item, player.getUniqueId().toString(), level);
            event.setCancelled(true);
            Messages.send(player, Messages.LOCKED_IT, Messages.LOCKED_IT_FORMAT);
            BlockLocation location = BlockLocation.from(block);
            triggerManager.fire(TriggerType.LOCK, new TriggerContext(player, location));
            triggerManager.fireForLock(lockTrigger, TriggerType.LOCK, new TriggerContext(player, location));
            // 锁等级未配置（撬锁时会降级回退）时恒输出日志便于排查配置问题；等级已配置时仅 debug 输出
            if (!lockConfigManager.isLevelConfigured(level) || config.isDebug()) {
                gameManager.getPlugin().getLogger().info(Messages.getLog(Messages.LOG_LOCK_APPLIED,
                        player.getName(), location, level));
            }
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
        String triggerData = itemManager.getLockTrigger(lockItem);
        if (triggerData != null) {
            triggerManager.fireForLock(triggerData, type, new TriggerContext(player, BlockLocation.from(block)));
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
                if (config.isKeyUnlockEnabled()) {
                    event.setCancelled(true);
                    // 卸锁需两次交互确认：第一次进入待确认，第二次（同一锁、未超时）才真正卸下
                    long now = System.currentTimeMillis();
                    UnlockConfirm pending = pendingUnlock.get(player.getUniqueId());
                    if (pending != null && pending.location.equals(location)
                            && now - pending.time <= UNLOCK_CONFIRM_TIMEOUT_MS) {
                        pendingUnlock.remove(player.getUniqueId());
                        unlockChest(event, player, block);
                    } else {
                        pendingUnlock.put(player.getUniqueId(), new UnlockConfirm(location, now));
                        Messages.send(player, Messages.UNLOCK_CONFIRM, Messages.UNLOCK_CONFIRM_FORMAT);
                    }
                } else {
                    // 卸锁功能关闭时放行，按普通点击打开箱子
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
        // 未配对钥匙：需启用配对功能；锁尚无配对钥匙时仅上锁者可配对，已有配对钥匙后任意持钥匙者可配对
        if (!config.isKeyPairEnabled()) {
            event.setCancelled(true);
            Messages.send(player, Messages.KEY_NOT_MATCHED, Messages.KEY_NOT_MATCHED_FORMAT);
            return;
        }
        event.setCancelled(true);
        String locker = chestService.getLocker(block);
        boolean isLocker = locker != null && locker.equals(player.getUniqueId().toString());
        if (!isLocker && !chestService.hasPairedKey(block)) {
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

    /** 卸下箱子上的锁：删除锁定记录并返还保存的锁物品。 */
    private void unlockChest(PlayerInteractEvent event, Player player, Block block) {
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
        Messages.send(player, Messages.UNLOCK_SUCCESS, Messages.UNLOCK_SUCCESS_FORMAT);
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

    private void handleGameClick(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        GameSession session = gameManager.getSession(player);
        if (session == null) {
            return;
        }

        if (session.checkSuccess()) {
            Messages.send(player, Messages.SUCCESS, Messages.SUCCESS_FORMAT);
            triggerManager.fire(TriggerType.SUCCESS, new TriggerContext(player, BlockLocation.from(session.getTarget())));
            fireLockTrigger(session.getTarget(), TriggerType.SUCCESS, player);
            // 战利品箱等自定义目标：成功后执行回调打开；否则打开开始撬锁时的目标箱子（判定时点击的可能是任意方块 / 空气）
            Runnable onSuccess = session.getOnSuccess();
            if (onSuccess != null) {
                onSuccess.run();
            } else {
                Block target = session.getTarget();
                if (target != null && target.getType() == Material.CHEST) {
                    target.getState().update(true);
                    player.openInventory(((org.bukkit.block.Chest) target.getState()).getInventory());
                    // 授予限时开箱授权：成功后在配置时长内可随时打开该箱子
                    gameManager.grantAccess(player, BlockLocation.from(target));
                }
            }
        } else {
            Messages.send(player, Messages.FAIL, Messages.FAIL_FORMAT);
            triggerManager.fire(TriggerType.FAIL, new TriggerContext(player, BlockLocation.from(session.getTarget())));
            fireLockTrigger(session.getTarget(), TriggerType.FAIL, player);
        }
        gameManager.endGame(player);
    }

    private void startNewGame(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        Block target = event.getClickedBlock();
        // 生效等级 = 锁等级 - 撬锁器等级；小于等于 0 时使用默认配置（getGameConfig 内部处理）
        int lockLevel = chestService.getLockLevel(target);
        int pickerLevel = itemManager.getLevel(event.getItem());
        int effectiveLevel = lockLevel - pickerLevel;
        GameConfig levelConfig = lockConfigManager.getGameConfig(effectiveLevel);
        // 开始提示与规则由 GameSession.start() 统一以标题显示（先规则后进度条），
        // 此处不再发 START_PICKING，避免同通道标题后发覆盖规则消息。
        if (gameManager.startGame(player, target, levelConfig) && config.isDebug()) {
            gameManager.getPlugin().getLogger().info(Messages.getLog(Messages.LOG_PICKLOCK_START_DEBUG,
                    player.getName(), BlockLocation.from(target), lockLevel, pickerLevel, effectiveLevel));
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && gameManager.isPlaying(event.getPlayer())) {
            GameSession session = gameManager.getSession(event.getPlayer());
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
        if (gameManager.isPlaying(event.getPlayer())) {
            gameManager.endGame(event.getPlayer());
        }
    }
}
