package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 撬锁小游戏会话：光标在进度条上往返移动，绿色光标进入红色区域时点击即可成功。
 */
public class GameSession {
    private final Player player;
    private final GameManager gameManager;
    private final PluginConfig config;
    private BukkitTask task;

    private int cursorPos = 0;
    private boolean movingRight = true;
    private int redStart;
    private int redLength;
    private int elapsedTicks = 0;

    public GameSession(Player player, GameManager gameManager, PluginConfig config) {
        this.player = player;
        this.gameManager = gameManager;
        this.config = config;
        initializeGame();
    }

    private void initializeGame() {
        redLength = config.getRedLength();
        redStart = ThreadLocalRandom.current().nextInt(3, config.getBarLength() - redLength - 2);
    }

    public void start() {
        Messages.send(player, Messages.RULE, Messages.RULE_FORMAT);
        task = gameManager.getPlugin().getServer().getScheduler().runTaskTimer(
                gameManager.getPlugin(),
                this::update,
                0,
                config.getMoveInterval()
        );
    }

    private void update() {
        if (++elapsedTicks >= config.getTimeoutTicks()) {
            Messages.send(player, Messages.GAME_TIMEOUT, Messages.GAME_TIMEOUT_FORMAT);
            gameManager.endGame(player);
            return;
        }
        moveCursor();
        sendProgressBar();
    }

    private void moveCursor() {
        int max = config.getBarLength() - 1;
        if (movingRight) {
            if (++cursorPos >= max) {
                movingRight = false;
            }
        } else {
            if (--cursorPos <= 0) {
                movingRight = true;
            }
        }
    }

    private void sendProgressBar() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < config.getBarLength(); i++) {
            if (i == cursorPos) {
                sb.append(ChatColor.GREEN).append('|');
            } else if (i >= redStart && i < redStart + redLength) {
                sb.append(ChatColor.RED).append('|');
            } else {
                sb.append(ChatColor.WHITE).append('|');
            }
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(sb.toString()));
    }

    public boolean checkSuccess() {
        return cursorPos >= redStart && cursorPos < redStart + redLength;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
    }
}
