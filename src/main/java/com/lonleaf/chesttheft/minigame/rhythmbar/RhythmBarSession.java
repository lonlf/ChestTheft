package com.lonleaf.chesttheft.minigame.rhythmbar;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 节奏条玩法：进度条上随机分布判定点，游标经过时点击命中，依次命中全部即成功；
 * 点击时不在判定点内或越过判定点未点击即失败。
 */
public class RhythmBarSession extends MiniGameSession {

    private final RhythmBarConfig barConfig;
    /** 骑乘模式控制器：仅 oscillate=false 时启用。 */
    private RidingController ridingController;
    private int cursorPos = 0;
    private boolean movingRight = true;
    /** 骑乘模式：netty 线程写入的当前方向（-1 左 / 0 无 / 1 右）。 */
    private volatile int inputDirection;
    /** 骑乘模式：上一帧方向与步进冷却（仅主线程读写）。 */
    private int lastInputDirection;
    private int stepCooldown;
    /** 已生成的判定点（从左到右排列），命中后转为完成态。 */
    private final List<HitBar> hitBars;
    /** 当前需命中的判定点下标（之前的均已命中）。 */
    private int currentIndex;

    public RhythmBarSession(MiniGameContext context) {
        super(context);
        this.barConfig = RhythmBarConfig.from(context.getConfig().getSection());
        this.hitBars = generateHitBars();
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
                        new TextComponent(Messages.get(barConfig.isOscillate() ? Messages.RULE_RHYTHM : Messages.RULE_RHYTHM_RIDE))),
                40L);
        if (!barConfig.isOscillate()) {
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
        int prev = cursorPos;
        if (barConfig.isOscillate()) {
            moveCursor();
        } else {
            applyKeyMovement();
        }
        // 光标刚离开当前判定点（上一帧在内、本帧在外）且未命中 → 视为错过，撬锁失败
        if (missedTarget(prev)) {
            failGame();
            return;
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
        // 游标移动音效（每格一声）
        barConfig.getMoveSound().play(player);
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
            stepCooldown = barConfig.getMoveInterval();
        } else {
            stepCooldown++;
        }
        lastInputDirection = dir;
        if (stepCooldown >= barConfig.getMoveInterval()) {
            cursorPos = Math.max(0, Math.min(barConfig.getBarLength() - 1, cursorPos + dir));
            stepCooldown = 0;
            // 游标移动音效（每格一声）
            barConfig.getMoveSound().play(player);
        }
    }

    /** 光标是否刚越过当前判定点（错过）：上一帧在判定点内、本帧已离开且未被点击命中。 */
    private boolean missedTarget(int prev) {
        HitBar target = currentTarget();
        if (target == null) {
            return false;
        }
        boolean wasIn = prev >= target.start && prev <= target.end;
        boolean nowIn = cursorPos >= target.start && cursorPos <= target.end;
        return wasIn && !nowIn;
    }

    /** 单次成功判定：全部判定点已命中视为成功（点击流程由 onClick 多阶段驱动）。 */
    @Override
    public boolean checkSuccess() {
        return currentIndex >= hitBars.size();
    }

    @Override
    public ClickResult onClick() {
        HitBar target = currentTarget();
        if (target != null && cursorPos >= target.start && cursorPos <= target.end) {
            // 命中当前判定点：下标前进即锁定该条（渲染转完成态），无需单独记录命中状态
            currentIndex++;
            // 命中判定点音效
            barConfig.getHitSound().play(player);
            // 命中后立即刷新进度条（转为完成态），无需等待下一个调度周期
            sendProgressBar();
            return currentIndex >= hitBars.size() ? ClickResult.SUCCESS : ClickResult.CONTINUE;
        }
        // 点击时不在当前判定点内 → 误点失败
        return ClickResult.FAIL;
    }

    /** 当前需命中的判定点；全部命中后返回 null。 */
    private HitBar currentTarget() {
        return currentIndex < hitBars.size() ? hitBars.get(currentIndex) : null;
    }

    /** 指定格是否属于已命中的判定点（完成态）。 */
    private boolean isHitAt(int i) {
        for (int j = 0; j < currentIndex; j++) {
            HitBar bar = hitBars.get(j);
            if (i >= bar.start && i <= bar.end) {
                return true;
            }
        }
        return false;
    }

    /** 指定格是否属于当前未命中的判定点（未完成态）。 */
    private boolean isTargetAt(int i) {
        HitBar target = currentTarget();
        return target != null && i >= target.start && i <= target.end;
    }

    /**
     * 生成判定点布局：判定点置于 1..barLength-2（两端为边框格），
     * 长度为 hitLength、间隔 ≥ hitMinGap，剩余空间随机分配到各判定点前。
     */
    private List<HitBar> generateHitBars() {
        int usable = barConfig.getBarLength() - 2;
        int count = barConfig.getHitCount();
        int hitLength = barConfig.getHitLength();
        int gap = barConfig.getHitMinGap();
        int minNeeded = count * hitLength + (count - 1) * gap;
        int slack = Math.max(0, usable - minNeeded);
        // 把 slack 随机拆成 count+1 段（判定点前空隙），前 count 段生效，末段即判定点后剩余空间
        List<Integer> gaps = splitRandom(slack, count + 1);
        List<HitBar> bars = new ArrayList<>(count);
        int pos = 1;
        for (int i = 0; i < count; i++) {
            pos += gaps.get(i);
            bars.add(new HitBar(pos, pos + hitLength - 1));
            pos += hitLength + gap;
        }
        return bars;
    }

    /** 把 total 随机拆成 parts 个非负整数：逐单位随机落入某一分段，保证各段非负且和为 total。 */
    private static List<Integer> splitRandom(int total, int parts) {
        List<Integer> result = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            result.add(0);
        }
        for (int i = 0; i < total; i++) {
            int bin = ThreadLocalRandom.current().nextInt(parts);
            result.set(bin, result.get(bin) + 1);
        }
        return result;
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
                // 游标（黄色）
                sb.append(ChatColor.YELLOW).append('|');
            } else if (isHitAt(i)) {
                // 已命中的判定点（绿色）
                sb.append(ChatColor.GREEN).append('|');
            } else if (isTargetAt(i)) {
                // 未命中的判定点（红色）
                sb.append(ChatColor.RED).append('|');
            } else {
                sb.append(ChatColor.WHITE).append('|');
            }
        }
        // 进度条显示在副标题上（title 留空白占位保持位置稳定）；fadeIn/fadeOut 为 0 避免刷新闪烁，stay 覆盖到下一次刷新
        player.sendTitle(" ", sb.toString(), 0, barConfig.getMoveInterval() + 1, 0);
    }

    /**
     * 位图渲染：与 moving-bar 同一套几何（边框格 + 普通格 + 间隙补偿 + 端点游标重叠），
     * 判定点分未完成格 / 完成格两态。
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
            } else if (isHitAt(i)) {
                json.text(String.valueOf(OffsetChars.doneChar()), OffsetChars.barFont());
                json.text(OffsetChars.raw(-cellGap), OffsetChars.offsetFont());
            } else if (isTargetAt(i)) {
                json.text(String.valueOf(OffsetChars.unhitChar()), OffsetChars.barFont());
                json.text(OffsetChars.raw(-cellGap), OffsetChars.offsetFont());
            } else {
                json.text(String.valueOf(OffsetChars.baseChar()), OffsetChars.barFont());
                json.text(OffsetChars.raw(-cellGap), OffsetChars.offsetFont());
            }
        }
        MiniMessageSender.sendTitle(player, "{\"text\":\" \"}", json.build(), 0, barConfig.getMoveInterval() + 1, 0);
    }

    /** 判定点：起始格与结束格（含端点）；命中状态由 currentIndex 下标隐含（下标之前的均已命中）。 */
    private static final class HitBar {
        final int start;
        final int end;

        HitBar(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
