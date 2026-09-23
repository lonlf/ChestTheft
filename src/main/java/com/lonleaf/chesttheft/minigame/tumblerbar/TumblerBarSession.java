package com.lonleaf.chesttheft.minigame.tumblerbar;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.message.BitmapCalculator;
import com.lonleaf.chesttheft.message.ChatJson;
import com.lonleaf.chesttheft.message.MiniMessageSender;
import com.lonleaf.chesttheft.message.OffsetChars;
import com.lonleaf.chesttheft.minigame.MiniGameContext;
import com.lonleaf.chesttheft.minigame.MiniGameSession;
import com.lonleaf.chesttheft.minigame.RidingController;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 机关转轮玩法：背景上重叠 A（上）/B（下）两图，A/D 调 A、W 转 B；
 * 仅 A 处于可解锁状态时 B 可达最终状态开锁，否则失败动画后复位重试。
 */
public class TumblerBarSession extends MiniGameSession {

    private final TumblerBarConfig barConfig;
    private RidingController ridingController;

    /** 骑乘输入（netty 线程写入）：当前方向（-1 左 / 0 无 / 1 右）与 W 键按住状态。 */
    private volatile int inputDirection;
    private volatile boolean forwardHeld;
    /** A/D 步进冷却（仅主线程读写）。 */
    private int lastInputDirection;
    private int stepCooldown;

    /** 机关 A 当前状态（0..aStates-1）。 */
    private int stateA;
    /** 本会话可解锁的 A 状态：unlock-a 配置为 -1 时每次撬锁随机选取，否则取配置值。 */
    private final int unlockA;
    /** 机关 B 当前状态（0..bStates-1）。 */
    private int stateB;
    /** 已按 W 锁定 A、转动 B 阶段。 */
    private boolean turningB;
    /** 失败动画剩余 tick（>0 时输入无效、A 显示特殊态）。 */
    private int failAnimTicks;
    /** 成功停顿剩余 tick（>0 时保持成功画面渲染并忽略输入，倒计时结束后再结算成功）。 */
    private int successHoldTicks;
    /** 累计尝试次数（每次失败 +1）。 */
    private int attempts;
    /** 复位后需等待 W 完全松开才允许重新锁定 A（避免按住 W 立即重试）。netty 线程写入、主线程读取，需 volatile 保证可见性。 */
    private volatile boolean wReleased;
    /** 规则提示 BossBar（屏幕顶部，与位图条零重叠、常驻不消失；会话结束时移除）。 */
    private BossBar ruleBar;

    public TumblerBarSession(MiniGameContext context) {
        super(context);
        this.barConfig = TumblerBarConfig.from(context.getConfig().getSection(), gameManager.getPlugin().getLogger());
        this.unlockA = barConfig.isRandomUnlockA()
                ? ThreadLocalRandom.current().nextInt(barConfig.getAStates())
                : barConfig.getUnlockA();
        this.stateA = barConfig.getInitialA();
        this.wReleased = true;
    }

    @Override
    protected int getTickInterval() {
        // 按键玩法需逐 tick 轮询输入保证响应
        return 1;
    }

    @Override
    protected void onStart() {
        // 规则提示显示在屏幕顶部 BossBar（与位图条零重叠、常驻不消失），会话结束时移除
        ruleBar = Bukkit.createBossBar(Messages.get(Messages.RULE_TUMBLER), BarColor.PURPLE, BarStyle.SOLID);
        ruleBar.setProgress(1.0);
        ruleBar.addPlayer(player);
        ruleBar.setVisible(true);
        // 骑乘模式：A/D 调整 A，W 转动 B
        ridingController = new RidingController(player,
                dir -> inputDirection = dir,
                forward -> {
                    forwardHeld = forward;
                    if (!forward) {
                        wReleased = true;
                    }
                });
        ridingController.start();
    }

    @Override
    protected void onStop() {
        if (ruleBar != null) {
            ruleBar.removePlayer(player);
            ruleBar.setVisible(false);
            ruleBar = null;
        }
        if (ridingController != null) {
            ridingController.stop();
            ridingController = null;
        }
    }

    /** B 可到达的最高状态：仅当 A 处于可解锁状态时可达最终状态。 */
    private int bMaxReachable() {
        return barConfig.getBStates() - 1 - Math.abs(stateA - unlockA);
    }

    @Override
    protected void onTick() {
        if (successHoldTicks > 0) {
            // 成功停顿：保持成功画面（A/B 停在最终位置）渲染，倒计时结束后再结算成功并关闭界面
            successHoldTicks--;
            if (successHoldTicks == 0) {
                gameManager.successGame(this);
            }
        } else if (failAnimTicks > 0) {
            tickFailAnim();
        } else if (turningB) {
            tickTurningB();
        } else {
            tickSelectA();
        }
        // 会话已结束（成功/失败/超时）时不再渲染，避免覆盖结束提示消息
        if (gameManager.isPlaying(player)) {
            sendProgressBar();
        }
    }

    /** 选择 A 阶段：A/D 调整 A（边界钳制），W 按下则锁定 A 并开始转动 B。 */
    private void tickSelectA() {
        if (forwardHeld && wReleased) {
            // 锁定 A，B 从初始状态开始转动
            turningB = true;
            stateB = 0;
            return;
        }
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
            int previous = stateA;
            stateA = Math.max(0, Math.min(barConfig.getAStates() - 1, stateA + dir));
            stepCooldown = 0;
            if (stateA != previous) {
                // A 转动声音：转到可解锁状态播放特殊提示音，其余状态播放普通转动音
                if (stateA == unlockA) {
                    barConfig.getAUnlockSound().play(player);
                } else {
                    barConfig.getAMoveSound().play(player);
                }
            }
        }
    }

    /** 转动 B 阶段：W 按住每 tick B+1；松开 W 则撤销本次尝试（B 归零、A 重新可用）；
     *  B 达可到达上限时判定成功或进入失败动画。 */
    private void tickTurningB() {
        if (!forwardHeld) {
            // 松开 W：撤销本次尝试，B 恢复初始状态，A 重新可用
            turningB = false;
            stateB = 0;
            inputDirection = 0;
            lastInputDirection = 0;
            stepCooldown = 0;
            return;
        }
        int max = bMaxReachable();
        if (stateB < max) {
            stateB++;
            // B 转动声音（每格一声，连续转动时有节奏反馈）
            barConfig.getBMoveSound().play(player);
        }
        if (stateB >= max) {
            if (max == barConfig.getBFinal()) {
                // A 处于可解锁状态：B 达最终状态，停顿 10 tick 展示成功画面后再结算
                successHoldTicks = 10;
            } else {
                startFailAnim();
            }
        }
    }

    /** 失败动画：W 失效、A 显示特殊态，持续 20 tick 后复位并计一次尝试。 */
    private void startFailAnim() {
        failAnimTicks = 20;
    }

    private void tickFailAnim() {
        if (--failAnimTicks > 0) {
            return;
        }
        failAnimTicks = 0;
        attempts++;
        if (attempts >= barConfig.getMaxAttempts()) {
            // 尝试次数用尽：撬锁失败
            gameManager.getPlugin().getLogger().info(Messages.getLog(Messages.LOG_TUMBLER_ATTEMPTS_EXHAUSTED,
                    attempts, barConfig.getMaxAttempts(), player.getName()));
            failGame();
            return;
        }
        // 复位：A 回初始状态（可解锁状态不变）、B 归零，等待 W 松开后重新尝试
        stateA = barConfig.getInitialA();
        stateB = 0;
        turningB = false;
        wReleased = !forwardHeld;
        inputDirection = 0;
        lastInputDirection = 0;
        stepCooldown = 0;
    }

    /** 当前 A 渲染字符：失败动画期间显示特殊态。 */
    private char currentAChar() {
        return failAnimTicks > 0 ? OffsetChars.aSpecialChar(stateA) : OffsetChars.aChar(stateA);
    }

    // ==================== 渲染 ====================

    private void sendProgressBar() {
        if (barConfig.isBitmap()) {
            sendBitmapBar();
        } else {
            sendAsciiBar();
        }
    }

    /** 状态文本（title，默认字体）：A/B 状态、可解锁标记与尝试计数。 */
    private String buildStatusText() {
        String aTag;
        if (failAnimTicks > 0) {
            aTag = ChatColor.RED + Messages.TUMBLER_DAMAGED;
        } else if (stateA == unlockA) {
            aTag = ChatColor.GREEN + Messages.TUMBLER_ALIGNED;
        } else {
            aTag = ChatColor.GRAY + Messages.TUMBLER_MISALIGNED;
        }
        return ChatColor.AQUA + "A:" + stateA + "[" + aTag + ChatColor.AQUA + "] "
                + ChatColor.BLUE + "B:" + stateB + ChatColor.DARK_AQUA + "/" + barConfig.getBFinal()
                + ChatColor.GOLD + "  " + Messages.ATTEMPTS_LABEL + " " + attempts + "/" + barConfig.getMaxAttempts();
    }

    /** ASCII 条渲染（默认）：不渲染背景格子（background-length 仅 bitmap 模式使用），
     *  条长自动覆盖 A/B 所在位置，空白处以空格占位保持布局；A 位置黄色（宽度 = tumbler-width，失败动画红色）、B 位置绿色数字。 */
    private void sendAsciiBar() {
        //暂时注释掉，不用这个了；但也许以后会有用
//        int cellPx = 8;
//        int aCell = barConfig.getAOffset() / cellPx;
//        int bCell = barConfig.getBOffset() / cellPx;
//        int widthCells = barConfig.getTumblerWidth();
//        // 条长 = A/B 中最右侧的位置 + 宽度，自动覆盖两个机关；不再依赖 background-length
//        int lengthCells = Math.max(aCell, bCell) + widthCells;
//        StringBuilder sb = new StringBuilder();
//        for (int i = 0; i < lengthCells; i++) {
//            if (i >= aCell && i < aCell + widthCells) {
//                if (i == aCell) {
//                    sb.append(failAnimTicks > 0 ? ChatColor.RED : ChatColor.YELLOW).append('A');
//                } else {
//                    sb.append(failAnimTicks > 0 ? ChatColor.RED : ChatColor.YELLOW).append('─');
//                }
//            } else if (i >= bCell && i < bCell + widthCells) {
//                if (i == bCell) {
//                    sb.append(ChatColor.GREEN).append((char) ('0' + Math.min(stateB, 9)));
//                } else {
//                    sb.append(ChatColor.GREEN).append('─');
//                }
//            } else {
//                // 空白占位：不渲染背景格子
//                sb.append(' ');
//            }
//        }
        // stay 用较大值（5 秒）并逐 tick 重置：客户端 titleTime 逐 tick 递减，若 stay 过小
        // （与服务端 tick 错位时）会短暂归零导致 title/subtitle 一起消失，表现为条整体闪动
//        player.sendTitle(buildStatusText(), sb.toString(), 0, 100, 0);
        player.sendTitle(buildStatusText(),null, 0, 100, 0);
    }

    /**
     * 位图渲染（参考 BetterHud 重叠方案）：背景整图上通过空间偏移重叠 A/B 透明图标，
     * 垂直由 PNG 内留白分层；title 留空占位、subtitle 显示位图条。
     */
    private void sendBitmapBar() {
        int tile = BitmapCalculator.tumblerTile();           // 背景格/A/B 图标宽（px），须与资源包一致
        int gap = BitmapCalculator.glyphGap();               // 每字形后固定间隙（px）
        int aX = barConfig.getAOffset();                     // A 在背景内的水平位置（px）
        int bX = barConfig.getBOffset();                     // B 在背景内的水平位置（px）
        int bgW = barConfig.getBackgroundLength() * tile;    // 背景总宽（px）
        List<Character> bgChars = OffsetChars.tumblerBgChars();
        int blocks = bgChars.size();                         // 背景切块数（客户端单字符字形 ≤256px，大背景须切块）
        if (gameManager.getPlugin().getConfig().getBoolean("debug")) {
            gameManager.getPlugin().getLogger().info(
                    "[TumblerBar] bitmap bar: tile=" + tile + " gap=" + gap + " aX=" + aX + " bX=" + bX
                            + " bgLen=" + barConfig.getBackgroundLength() + " bgW=" + bgW + " blocks=" + blocks
                            + " back=" + -(bgW + blocks * gap - aX)
                            + " off=" + (bX - aX - tile - gap)
                            + " tail=" + (bgW - bX - tile - gap));
        }
        ChatJson json = ChatJson.create().noShadow();
        // 整条水平平移（纯服务端，无需材质包）：subtitle 居中渲染下，串首前缀偏移字符推进 d px
        // 会使主体相对居中位置右移 d/2 px，故实际写入 2×配置值（偶数避免半像素取整）
        int horizontal = BitmapCalculator.horizontalOffset();
        if (horizontal != 0) {
            json.text(OffsetChars.raw(horizontal * 2), OffsetChars.offsetFont());
        }
        // 背景块按序并列铺满整条（每块 advance = 背景总宽 / 块数）
        for (char c : bgChars) {
            json.text(String.valueOf(c), OffsetChars.barFont());
        }
        // 先渲染 B 再渲染 A：后绘制者在字形层叠中位于上层，因此 A 覆盖 B（重叠区域以 A 为准）
        // 负偏移回退到 B 位置（含各背景块字形后的引擎间隙补偿），B 重叠绘制在背景之上
        json.text(OffsetChars.raw(-(bgW + blocks * gap - bX)), OffsetChars.offsetFont());
        json.text(String.valueOf(OffsetChars.bChar(stateB)), OffsetChars.barFont());
        // 偏移到 A 位置（A 与 B 水平可同可异，垂直由 PNG 内留白分层）
        json.text(OffsetChars.raw(aX - bX - tile - gap), OffsetChars.offsetFont());
        json.text(String.valueOf(currentAChar()), OffsetChars.barFont());
        // 正偏移收尾到背景右端（以最后绘制的 A 为基准，保持总宽，标题居中稳定）
        json.text(OffsetChars.raw(bgW - aX - tile - gap), OffsetChars.offsetFont());
        // bitmap 模式纯视觉模拟撬锁过程（撬锁器 / 锁芯），不显示状态文字；title 留空占位保持条位置稳定，规则提示在顶部 BossBar
        // stay 用较大值（5 秒）并逐 tick 重置，避免 titleTime 归零导致条整体闪动（见 sendAsciiBar 注释）
        MiniMessageSender.sendTitle(player, ChatJson.create().text(" ").build(),
                json.build(), 0, 100, 0);
    }

    @Override
    public ClickResult onClick() {
        // 本玩法由按键驱动（A/D/W），鼠标点击不参与判定：忽略点击，避免骑乘操作中误触鼠标判负
        return ClickResult.CONTINUE;
    }

    @Override
    public boolean checkSuccess() {
        // 本玩法由按键驱动，不响应鼠标点击判定
        return false;
    }
}
