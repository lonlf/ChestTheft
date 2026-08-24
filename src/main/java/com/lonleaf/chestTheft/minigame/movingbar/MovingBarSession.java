package com.lonleaf.chesttheft.minigame.movingbar;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.message.BitmapCalculator;
import com.lonleaf.chesttheft.message.ChatJson;
import com.lonleaf.chesttheft.message.MiniMessageSender;
import com.lonleaf.chesttheft.message.OffsetChars;
import com.lonleaf.chesttheft.minigame.MiniGameContext;
import com.lonleaf.chesttheft.minigame.MiniGameSession;
import com.lonleaf.chesttheft.minigame.RidingController;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 移动游标玩法规则：光标在进度条上移动，玩家需在光标进入未完成判定区时点击判定。
 * 支持两种游标控制：oscillate=true 自动往复移动；false 骑乘模式（A/D 键控制左右移动）。
 */
public class MovingBarSession extends MiniGameSession {

    private final MovingBarConfig barConfig;
    /** 骑乘模式控制器：仅 oscillate=false 时启用。 */
    private RidingController ridingController;
    private int cursorPos = 0;
    private boolean movingRight = true;
    private int unhitStart;
    private int unhitLength;
    /** 骑乘模式：netty 线程写入的当前方向（-1 左 / 0 无 / 1 右）。 */
    private volatile int inputDirection;
    /** 骑乘模式：上一帧方向与步进冷却（仅主线程读写）。 */
    private int lastInputDirection;
    private int stepCooldown;

    public MovingBarSession(MiniGameContext context) {
        super(context);
        this.barConfig = MovingBarConfig.from(context.getConfig().getSection());
        this.unhitLength = barConfig.getUnhitLength();
        // 条太短（bar-length - unhit-length - 2 ≤ 3）时 nextInt(3, upper) 会因上界不足抛异常：固定取 3，判定区紧贴左端
        int upper = barConfig.getBarLength() - unhitLength - 2;
        this.unhitStart = upper > 3 ? ThreadLocalRandom.current().nextInt(3, upper) : 3;
    }

    @Override
    protected int getTickInterval() {
        // 骑乘模式需逐 tick 轮询按键方向保证响应；往复模式按移动间隔调度即可
        return barConfig.isOscillate() ? barConfig.getMoveInterval() : 1;
    }

    @Override
    protected void onStart() {
        // 规则提示在动作栏（周期重发保持持续显示），与进度条标题不同通道互不覆盖
        repeatActionBar(() -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(Messages.get(barConfig.isOscillate() ? Messages.RULE : Messages.RULE_RIDE))),
                40L);
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
                sb.append(ChatColor.YELLOW).append('|');
            } else if (i >= unhitStart && i < unhitStart + unhitLength) {
                sb.append(ChatColor.RED).append('|');
            } else {
                sb.append(ChatColor.WHITE).append('|');
            }
        }
        // 进度条显示在副标题上（title 留空白占位保持位置稳定）；fadeIn/fadeOut 为 0 避免刷新闪烁，stay 覆盖到下一次刷新
        player.sendTitle(" ", sb.toString(), 0, barConfig.getMoveInterval() + 1, 0);
    }

    /**
     * 位图渲染：两端边框格 + 中间普通格；游标在端点与边框格重叠（覆盖主体、露出外侧边框）。
     * 相邻字符间插入间隙补偿偏移使格子无缝贴合，总宽固定为 barLength 格。
     */
    private void sendBitmapBar() {
        int barLength = barConfig.getBarLength();
        int cellGap = BitmapCalculator.cellGap();
        int edgeGap = BitmapCalculator.edgeGap();
        int leftBack = BitmapCalculator.leftOverlayBack();
        int leftTail = BitmapCalculator.leftOverlayTail();
        int rightBack = BitmapCalculator.rightOverlayBack();
        ChatJson json = ChatJson.create().noShadow();
        for (int i = 0; i < barLength; i++) {
            boolean last = i == barLength - 1;
            if (i == cursorPos) {
                if (i == 0) {
                    // 左端游标：边框格打底，回退到其内部再画游标，露出外侧 1px 边框
                    json.text(String.valueOf(OffsetChars.leftBorderChar()), OffsetChars.barFont());
                    json.text(OffsetChars.raw(-leftBack), OffsetChars.offsetFont());
                    json.text(String.valueOf(OffsetChars.cursorChar()), OffsetChars.barFont());
                    if (!last) {
                        json.text(OffsetChars.raw(-leftTail), OffsetChars.offsetFont());
                    }
                } else if (last) {
                    // 右端游标：边框格打底，回退到其起点再画游标，露出外侧 1px 边框
                    json.text(String.valueOf(OffsetChars.rightBorderChar()), OffsetChars.barFont());
                    json.text(OffsetChars.raw(-rightBack), OffsetChars.offsetFont());
                    json.text(String.valueOf(OffsetChars.cursorChar()), OffsetChars.barFont());
                } else {
                    json.text(String.valueOf(OffsetChars.cursorChar()), OffsetChars.barFont());
                    json.text(OffsetChars.raw(-cellGap), OffsetChars.offsetFont());
                }
            } else if (i == 0) {
                json.text(String.valueOf(OffsetChars.leftBorderChar()), OffsetChars.barFont());
                if (!last) {
                    json.text(OffsetChars.raw(-edgeGap), OffsetChars.offsetFont());
                }
            } else if (last) {
                json.text(String.valueOf(OffsetChars.rightBorderChar()), OffsetChars.barFont());
            } else if (i >= unhitStart && i < unhitStart + unhitLength) {
                json.text(String.valueOf(OffsetChars.unhitChar()), OffsetChars.barFont());
                json.text(OffsetChars.raw(-cellGap), OffsetChars.offsetFont());
            } else {
                json.text(String.valueOf(OffsetChars.baseChar()), OffsetChars.barFont());
                json.text(OffsetChars.raw(-cellGap), OffsetChars.offsetFont());
            }
        }
        MiniMessageSender.sendTitle(player, "{\"text\":\" \"}", json.build(), 0, barConfig.getMoveInterval() + 1, 0);
    }

    @Override
    public boolean checkSuccess() {
        return cursorPos >= unhitStart && cursorPos < unhitStart + unhitLength;
    }
}
