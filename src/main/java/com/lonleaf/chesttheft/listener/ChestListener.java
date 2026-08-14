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

import java.util.Map;

public class ChestListener implements Listener {
    private final ChestService chestService;
    private final GameManager gameManager;
    private final ItemManager itemManager;
    private final PluginConfig config;

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
                } else if (itemManager.isPairedTo(item, location)) {
                    // 已配对的钥匙可直接打开箱子
                    event.setCancelled(false);
                } else {
                    event.setCancelled(true);
                    player.sendMessage(Messages.get(Messages.KEY_NOT_MATCHED));
                }
            } else if (!config.isKeyRequireInHand() && hasMatchedBagKey(player, location)) {
                // 背包中有与该锁配对的钥匙时可直接打开
                event.setCancelled(false);
            } else {
                event.setCancelled(true);
            }
        } else if (item != null && itemManager.isType(item, ItemType.LOCK)) {
            chestService.lock(block, item, player.getUniqueId().toString());
            event.setCancelled(true);
            player.sendTitle(Messages.get(Messages.LOCKED_IT), "", 6, 20, 6);
        }
    }

    /** 是否为钥匙交互按键组合（潜行 + 配置动作），钥匙配对与卸锁共用此按键。 */
    private boolean isKeyInteractionPress(PlayerInteractEvent event, Player player) {
        return event.getAction() == config.getKeyInteractionAction()
                && player.isSneaking() == config.isKeyInteractionSneak();
    }

    /** 交互按键处理：已配对钥匙卸下锁，未配对钥匙由锁主配对，配对其他锁的钥匙不可用。 */
    private void handleKeyPress(PlayerInteractEvent event, Player player, Block block,
                                BlockLocation location, ItemStack key) {
        if (itemManager.isPairedTo(key, location)) {
            if (config.isKeyUnlockEnabled()) {
                event.setCancelled(true);
                unlockChest(event, player, block);
            }
            // 卸锁功能关闭时放行，按普通点击打开箱子
            return;
        }
        if (itemManager.getPairedLock(key) != null) {
            event.setCancelled(true);
            player.sendMessage(Messages.get(Messages.KEY_NOT_MATCHED));
            return;
        }
        // 未配对钥匙：需启用配对功能且仅上锁者可将其与锁配对
        if (!config.isKeyPairEnabled()) {
            event.setCancelled(true);
            player.sendMessage(Messages.get(Messages.KEY_NOT_MATCHED));
            return;
        }
        event.setCancelled(true);
        String locker = chestService.getLocker(block);
        if (locker == null || !locker.equals(player.getUniqueId().toString())) {
            player.sendMessage(Messages.get(Messages.PAIR_NOT_LOCKER));
            return;
        }
        itemManager.setPairedLock(key, location);
        player.sendMessage(Messages.get(Messages.PAIR_SUCCESS));
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
        player.sendMessage(Messages.get(Messages.UNLOCK_SUCCESS));
    }

    /** 背包中是否有与指定锁配对的钥匙；仅当配置允许背包取用时生效（require-in-hand: false）。 */
    private boolean hasMatchedBagKey(Player player, BlockLocation location) {
        if (config.isKeyRequireInHand()) {
            return false;
        }
        for (ItemStack content : player.getInventory().getContents()) {
            if (content != null && itemManager.isType(content, ItemType.KEY)
                    && itemManager.isPairedTo(content, location)) {
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
            player.sendMessage(Messages.get(Messages.SUCCESS));
            event.getClickedBlock().getState().update(true);
            player.openInventory(((org.bukkit.block.Chest) event.getClickedBlock().getState()).getInventory());
        } else {
            player.sendMessage(Messages.get(Messages.FAIL));
        }
        gameManager.endGame(player);
    }

    private void startNewGame(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        if (gameManager.startGame(player)) {
            player.sendMessage(Messages.get(Messages.START_PICKING));
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && gameManager.isPlaying(event.getPlayer())) {
            gameManager.endGame(event.getPlayer());
            event.getPlayer().sendMessage(Messages.get(Messages.CANCEL_PICKING));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (gameManager.isPlaying(event.getPlayer())) {
            gameManager.endGame(event.getPlayer());
        }
    }
}
