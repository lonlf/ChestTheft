package com.lonleaf.chesttheft.minigame.game.movingbar;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.message.ChatJson;
import com.lonleaf.chesttheft.message.MiniMessageSender;
import com.lonleaf.chesttheft.message.OffsetChars;
import com.lonleaf.chesttheft.minigame.MiniGameContext;
import com.lonleaf.chesttheft.minigame.MiniGameSession;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 移动游标玩法规则：光标在进度条上移动，玩家需在光标进入红区时点击判定。
 * 支持两种游标控制：oscillate=true 自动往复移动；false 骑乘模式（A/D 键控制左右移动）。
 */
public class MovingBarSession extends MiniGameSession {

    private final MovingBarConfig barConfig;
    /** 骑乘模式控制器：仅 oscillate=false 时启用。 */
    private RidingController ridingController;
    private int cursorPos = 0;
    private boolean movingRight = true;
    private int redStart;
    private int redLength;
    /** 骑乘模式：netty 线程写入的当前方向（-1 左 / 0 无 / 1 右）。 */
    private volatile int inputDirection;
    /** 骑乘模式：上一帧方向与步进冷却（仅主线程读写）。 */
    private int lastInputDirection;
    private int stepCooldown;

    public MovingBarSession(MiniGameContext context) {
        super(context);
        this.barConfig = MovingBarConfig.from(context.getConfig().getSection());
        this.redLength = barConfig.getRedLength();
        this.redStart = ThreadLocalRandom.current().nextInt(3, barConfig.getBarLength() - redLength - 2);
    }

    @Override
    protected int getTickInterval() {
        // 骑乘模式需逐 tick 轮询按键方向保证响应；往复模式按移动间隔调度即可
        return barConfig.isOscillate() ? barConfig.getMoveInterval() : 1;
    }

    @Override
    protected void onStart() {
        // 规则提示固定显示在动作栏（不参与配置自定义），与进度条标题同步启动；不同通道互不覆盖
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(Messages.get(Messages.RULE)));
        if (!barConfig.isOscillate()) {
            // 骑乘模式：发包让玩家骑上隐形坐骑，A/D 键控制游标
            ridingController = new RidingController(player, dir -> inputDirection = dir);
            ridingController.start();
        }
    }

    @Override
    protected void onStop() {
        if (ridingController != null) {
            ridingController.stop();
            ridingController = null;
        }
    }

    @Override
    protected void onTick() {
        if (barConfig.isOscillate()) {
            moveCursor();
        } else {
            applyKeyMovement();
        }
        sendProgressBar();
    }

    /** 往复模式：光标自动在两端间往返移动。 */
    private void moveCursor() {
        int max = barConfig.getBarLength() - 1;
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

    /** 骑乘模式：按当前输入方向移动光标，长按按移动间隔连续移动，换向立即生效。 */
    private void applyKeyMovement() {
        int dir = inputDirection;
        if (dir == 0) {
            stepCooldown = 0;
            lastInputDirection = 0;
            return;
        }
        if (lastInputDirection != dir) {
            // 新方向按下：立即移动一格
            stepCooldown = barConfig.getMoveInterval();
        } else {
            stepCooldown++;
        }
        lastInputDirection = dir;
        if (stepCooldown >= barConfig.getMoveInterval()) {
            cursorPos = Math.max(0, Math.min(barConfig.getBarLength() - 1, cursorPos + dir));
            stepCooldown = 0;
        }
    }

    private void sendProgressBar() {
        if (barConfig.isBitmap()) {
            sendBitmapBar();
            return;
        }
        sendAsciiBar();
    }

    /** ASCII 字符条渲染（默认）：Spigot legacy 通道，无需材质包。 */
    private void sendAsciiBar() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < barConfig.getBarLength(); i++) {
            if (i == cursorPos) {
                sb.append(ChatColor.GREEN).append('|');
            } else if (i >= redStart && i < redStart + redLength) {
                sb.append(ChatColor.RED).append('|');
            } else {
                sb.append(ChatColor.WHITE).append('|');
            }
        }
        // 进度条显示在标题上；fadeIn/fadeOut 为 0 避免刷新闪烁，stay 覆盖到下一次刷新
        player.sendTitle(sb.toString(), "", 0, barConfig.getMoveInterval() + 1, 0);
    }

    /**
     * 位图渲染：逐格选择底格/红格/指针格字符，在相邻格之间插入 -1px 偏移字符
     * 补偿客户端渲染中每个字形后固定的 1px 间隙（引擎行为），使格子无缝贴合。
     * 总宽仍为 barLength 格，title 保持居中。
     */
    private void sendBitmapBar() {
        int barLength = barConfig.getBarLength();
        ChatJson json = ChatJson.create().noShadow();
        for (int i = 0; i < barLength; i++) {
            char c;
            if (i == cursorPos) {
                c = OffsetChars.pointerChar();
            } else if (i >= redStart && i < redStart + redLength) {
                c = OffsetChars.redChar();
            } else {
                c = OffsetChars.baseChar();
            }
            json.text(String.valueOf(c), OffsetChars.barFont());
            if (i < barLength - 1) {
                json.text(OffsetChars.raw(-1), OffsetChars.offsetFont());
            }
        }
        MiniMessageSender.sendTitle(player, json.build(), null, 0, barConfig.getMoveInterval() + 1, 0);
    }

    @Override
    public boolean checkSuccess() {
        return cursorPos >= redStart && cursorPos < redStart + redLength;
    }
}
