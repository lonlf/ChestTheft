package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.Messages;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

public class GameSession {
    private final Player player;
    private final GameManager gameManager;
    private final GameConfig config;
    /** 开始撬锁时的目标箱子：判定成功后打开此箱子。 */
    private final Block target;
    private BukkitTask task;

    private int cursorPos = 0;
    private boolean movingRight = true;
    private int redStart;
    private int redLength;
    private int elapsedTicks = 0;

    public GameSession(Player player, GameManager gameManager, GameConfig config, Block target) {
        this.player = player;
        this.gameManager = gameManager;
        this.config = config;
        this.target = target;
        initializeGame();
    }

    private void initializeGame() {
        redLength = config.getRedLength();
        redStart = ThreadLocalRandom.current().nextInt(3, config.getBarLength() - redLength - 2);
    }

    public void start() {
        // 规则提示固定显示在动作栏（不参与配置自定义），与进度条标题同步启动；不同通道互不覆盖
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(Messages.get(Messages.RULE)));
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
        // 进度条显示在标题上；fadeIn/fadeOut 为 0 避免刷新闪烁，stay 覆盖到下一次刷新
        player.sendTitle(sb.toString(), "", 0, config.getMoveInterval() + 1, 0);
    }

    public boolean checkSuccess() {
        return cursorPos >= redStart && cursorPos < redStart + redLength;
    }

    public Block getTarget() {
        return target;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // 不清空标题：结束提示（成功/失败/超时/取消）会以标题覆盖进度条，此处清空反而会让提示消失
    }
}
