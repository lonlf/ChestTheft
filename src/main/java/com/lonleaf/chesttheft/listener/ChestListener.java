package com.lonleaf.chesttheft.listener;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.minigame.GameManager;
import com.lonleaf.chesttheft.minigame.GameSession;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
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
    /** 卸锁确认状态：玩家待确认的卸锁目标（位置 + 时间戳），需交互两次才真正卸锁。 */
    private final Map<UUID, UnlockConfirm> pendingUnlock = new HashMap<>();
    /** 卸锁确认窗口（毫秒），超时后需重新发起确认。 */
    private static final long UNLOCK_CONFIRM_TIMEOUT_MS = 5000L;

    public ChestListener(ChestService chestService, GameManager gameManager,
                         ItemManager itemManager, PluginConfig config) {
        this.chestService = chestService;
        this.gameManager = gameManager;
        this.itemManager = itemManager;
        this.config = config;
    }

    @EventHandler
    public void onChestClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        if (chestService.isLocked(block)) {
            BlockLocation location = BlockLocation.from(block);
            ItemType handType = item == null ? null : itemManager.getType(item);
            if (handType == ItemType.PICKER) {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block.getType() != Material.CHEST) {
                    return;
                }
                if (gameManager.isPlaying(player)) {
                    handleGameClick(event, player);
                } else {
                    startNewGame(event, player);
                }
            } else if (handType == ItemType.KEY) {
                if (isKeyInteractionPress(event, player)) {
                    // 交互按键：未配对钥匙由上锁者配对；已配对钥匙卸下锁
                    handleKeyPress(event, player, block, location, item);
                } else if (isKeyMatched(item, location, block)) {
                    // 位置与凭证均匹配的钥匙可直接打开箱子
                    event.setCancelled(false);
                } else if (itemManager.isPairedTo(item, location)) {
                    // 位置匹配但凭证不匹配：锁已更换，旧钥匙失效
                    event.setCancelled(true);
                    Messages.send(player, Messages.KEY_CHANGED, Messages.KEY_CHANGED_FORMAT);
                } else {
                    event.setCancelled(true);
                    Messages.send(player, Messages.KEY_NOT_MATCHED, Messages.KEY_NOT_MATCHED_FORMAT);
                }
            } else if (!config.isKeyRequireInHand() && hasMatchedBagKey(player, block, location)) {
                // 背包中有与该锁配对的钥匙时可直接打开
                event.setCancelled(false);
            } else {
                // 非特殊物品（或空手）点击上锁箱子：拦截并提示
                event.setCancelled(true);
                Messages.send(player, Messages.CHEST_LOCKED, Messages.CHEST_LOCKED_FORMAT);
            }
        } else if (item != null && itemManager.isType(item, ItemType.LOCK)) {
            chestService.lock(block, item, player.getUniqueId().toString());
            event.setCancelled(true);
            Messages.send(player, Messages.LOCKED_IT, Messages.LOCKED_IT_FORMAT);
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
                }
                // 卸锁功能关闭时放行，按普通点击打开箱子
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
            event.getClickedBlock().getState().update(true);
            player.openInventory(((org.bukkit.block.Chest) event.getClickedBlock().getState()).getInventory());
        } else {
            Messages.send(player, Messages.FAIL, Messages.FAIL_FORMAT);
        }
        gameManager.endGame(player);
    }

    private void startNewGame(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        if (gameManager.startGame(player)) {
            Messages.send(player, Messages.START_PICKING, Messages.START_PICKING_FORMAT);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && gameManager.isPlaying(event.getPlayer())) {
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
