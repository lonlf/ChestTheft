package com.lonleaf.chestTheft;

import com.lonleaf.chestTheft.ChestData.ChestUtils;
import com.lonleaf.chestTheft.Game.GameManager;
import com.lonleaf.chestTheft.Game.GameSession;
import com.lonleaf.chestTheft.PCD.ItemPCDUtil;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.Buffer;

import static com.lonleaf.chestTheft.ChestTheft.instance;

public class ChestListener implements Listener {
    private final GameManager gameManager;

    public ChestListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onChestClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack itemStack = event.getItem();
        Block chest = event.getClickedBlock();
        if (chest==null)return;
        if(ChestUtils.isLocked(chest)) {
            if(itemStack==null){
                event.setCancelled(true);
                return;
            }
            if (ItemPCDUtil.isPicker(itemStack)) {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

                Block clickedBlock = event.getClickedBlock();
                if (clickedBlock == null || clickedBlock.getType() != Material.CHEST) return;

                if (gameManager.isPlaying(player)) {
                    handleGameClick(event, player);
                } else {
                    startNewGame(event, player);
                }
            } else if (ItemPCDUtil.isKey(itemStack)) {
                event.setCancelled(false);
            }
        }else if (itemStack!=null && !ChestUtils.isLocked(chest)&&ItemPCDUtil.isLock(itemStack)){
            ChestUtils.lock(chest);
            event.setCancelled(true);
            player.sendTitle(instance.language("lockedIt"),"",6,20,6);
        }
    }

    private void handleGameClick(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        GameSession session = gameManager.activeGames.get(player.getUniqueId());

        if (session.checkSuccess()) {
            player.sendMessage(instance.language("success"));
            // 实际打开箱子的逻辑
            event.getClickedBlock().getState().update(true);
            player.openInventory(((org.bukkit.block.Chest)
                    event.getClickedBlock().getState()).getInventory());
        } else {
            player.sendMessage(instance.language("fail"));
        }
        gameManager.endGame(player);
    }

    private void startNewGame(PlayerInteractEvent event, Player player) {
        event.setCancelled(true);
        gameManager.startGame(player);
        player.sendMessage(instance.language("startePicking"));
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && gameManager.isPlaying(event.getPlayer())) {
            gameManager.endGame(event.getPlayer());
            event.getPlayer().sendMessage(instance.language("cancelPicking"));
        }
    }
}
