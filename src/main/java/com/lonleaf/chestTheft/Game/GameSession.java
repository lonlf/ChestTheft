package com.lonleaf.chestTheft.Game;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.concurrent.ThreadLocalRandom;

import static com.lonleaf.chestTheft.ChestTheft.instance;

// 游戏会话抽象
public class GameSession {
    private static final int TOTAL_LENGTH = 15;
    private static final int RED_START = 5;
    private static final int RED_LENGTH = 3;
    private static final long MOVE_INTERVAL = 10; // 半秒移动一次

    private final Player player;
    private final GameManager gameManager;
    private BukkitTask task;

    private int cursorPos = 0;
    private boolean movingRight = true;
    private int redStart;
    private int redLength;

    public GameSession(Player player, GameManager gameManager) {
        this.player = player;
        this.gameManager = gameManager;
        initializeGame();
    }

    private void initializeGame() {
        // 随机生成红色区域
        redStart = ThreadLocalRandom.current().nextInt(3, TOTAL_LENGTH - RED_LENGTH - 2);
        redLength = RED_LENGTH;
    }

    public void start() {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,new TextComponent(instance.language("rule")));
        task = gameManager.plugin.getServer().getScheduler().runTaskTimer(
                gameManager.plugin,
                this::update,
                0,
                MOVE_INTERVAL
        );
    }

    private void update() {
        moveCursor();
        sendProgressBar();
    }

    private void moveCursor() {
        if (movingRight) {
            if (++cursorPos >= TOTAL_LENGTH - 1) movingRight = false;
        } else {
            if (--cursorPos <= 0) movingRight = true;
        }
    }

    private void sendProgressBar() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TOTAL_LENGTH; i++) {
            if (i == cursorPos) {
                sb.append(ChatColor.GREEN).append("|");
            } else if (i >= redStart && i < redStart + redLength) {
                sb.append(ChatColor.RED).append("|");
            } else {
                sb.append(ChatColor.WHITE).append("|");
            }
        }
        player.sendTitle(sb.toString(),"",10,60,10);
    }

    public boolean checkSuccess() {
        return cursorPos >= redStart && cursorPos < redStart + redLength;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,new TextComponent(""));
        player.sendTitle("","",1,1,1);
    }
}
