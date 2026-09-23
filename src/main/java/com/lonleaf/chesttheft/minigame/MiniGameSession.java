package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 小游戏会话抽象基类：封装单局公共生命周期（调度循环、超时、结束），玩法规则由子类实现。
 */
public abstract class MiniGameSession {

    /** 点击结果：单次点击对游戏进程的影响。 */
    public enum ClickResult {
        /** 点击成功：游戏成功结束（如移动游标命中判定区、节奏条全部判定点命中）。 */
        SUCCESS,
        /** 点击失败：游戏失败结束（如未命中判定区、误点）。 */
        FAIL,
        /** 命中但游戏继续（仅多阶段玩法使用，如节奏条命中一个判定点后需继续命中其余判定点）。 */
        CONTINUE
    }

    protected final Player player;
    protected final GameManager gameManager;
    protected final GameConfig config;
    /** 撬锁成功回调（战利品箱等自定义目标）：不为 null 时成功后执行回调而非打开原版箱子。 */
    protected final Runnable onSuccess;
    /** 开始撬锁时的目标箱子：判定成功后打开此箱子。 */
    protected final Block target;
    /** 开始撬锁时的玩家位置：移动超范围中断判定基准。 */
    protected final Location startLocation;
    /** 超时截止时间戳（壁钟，与玩法调度频率解耦）。 */
    private final long deadline;

    private BukkitTask task;
    /** 动作栏规则提示的周期重发任务：客户端动作栏约 3 秒后自动消失，重发使其持续显示。 */
    private BukkitTask ruleTask;
    /** 结算闸门：点击、玩法自身、超时、中断可能并发触发结算，保证只有首个生效。 */
    private final AtomicBoolean settled = new AtomicBoolean();

    protected MiniGameSession(MiniGameContext context) {
        this.player = context.getPlayer();
        this.gameManager = context.getGameManager();
        this.config = context.getConfig();
        this.onSuccess = context.getOnSuccess();
        this.target = context.getTarget();
        this.startLocation = context.getStartLocation();
        this.deadline = System.currentTimeMillis() + config.getTimeoutSeconds() * 1000L;
    }

    /** 玩法循环周期（tick）：子类按自身节奏返回（如 moving-bar 的移动间隔）。 */
    protected abstract int getTickInterval();

    /** 会话启动钩子：子类在此显示规则提示 / 初始化玩法状态。 */
    protected abstract void onStart();

    /** 会话结束钩子：子类在此清理占用的资源（如骑乘实体），默认空实现。 */
    protected void onStop() {
    }

    /** 每周期更新：子类实现玩法推进与 UI 渲染。 */
    protected abstract void onTick();

    /** 成功判定：由外部交互（点击/动作）在恰当时机调用。 */
    public abstract boolean checkSuccess();

    /** 点击判定：默认按 checkSuccess 单次判定；多阶段玩法可覆写返回 CONTINUE 继续。 */
    public ClickResult onClick() {
        return checkSuccess() ? ClickResult.SUCCESS : ClickResult.FAIL;
    }

    /** 玩法主动失败结束（非点击触发，如节奏条光标越过判定点未命中）：发送失败消息并触发失败触发器后结束会话。 */
    public final void failGame() {
        gameManager.failGame(this);
    }

    /** 标记本局进入结算：仅首个调用返回 true；重复调用返回 false（幂等闸门，防重复发奖/重复惩罚）。 */
    public boolean markSettled() {
        return settled.compareAndSet(false, true);
    }

    /** 启动会话：注册定时任务并进入玩法循环。 */
    public final void start() {
        onStart();
        task = gameManager.getPlugin().getServer().getScheduler().runTaskTimer(
                gameManager.getPlugin(), this::update, 0, getTickInterval());
    }

    private void update() {
        if (settled.get()) {
            return;   // 已结算：不再推进（若结算过程中抛异常，避免每 tick 重复触发）
        }
        try {
            if (System.currentTimeMillis() >= deadline) {
                // 超时按失败处理：保留超时提示，仍触发失败触发器/失败音效/锁物品触发器
                // （与主动失败一致的惩罚语义，避免玩家挂机等超时规避失败惩罚）
                Messages.send(player, Messages.GAME_TIMEOUT, Messages.GAME_TIMEOUT_FORMAT);
                gameManager.failGame(this, false);
                return;
            }
            onTick();
        } catch (Exception e) {
            // 玩法推进/渲染异常：按失败收尾，避免玩家永久停留在游戏中（isPlaying 恒 true 会劫持所有点击）
            gameManager.getPlugin().getLogger().log(Level.WARNING,
                    "[ChestTheft] Minigame tick failed, session aborted", e);
            gameManager.failGame(this, false);
        }
    }

    /** 结束会话：取消定时任务与规则提示重发任务，并调用清理钩子（结束提示由调用方以标题覆盖，此处不清空标题）。 */
    public final void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (ruleTask != null) {
            ruleTask.cancel();
            ruleTask = null;
        }
        try {
            onStop();
        } catch (Exception e) {
            // 清理钩子异常（坐骑/展示实体释放等）不得影响任务取消与调用方的后续状态清理
            gameManager.getPlugin().getLogger().log(Level.WARNING,
                    "[ChestTheft] Minigame session cleanup failed", e);
        }
    }

    /**
     * 周期重发动作栏消息：客户端动作栏约 3 秒后自动消失，按 intervalTicks 周期重发使其持续显示到会话结束。
     * 立即发送一次，随后按间隔重复调用 sender。
     */
    protected final void repeatActionBar(Runnable sender, long intervalTicks) {
        sender.run();
        if (ruleTask != null) {
            ruleTask.cancel();
        }
        ruleTask = gameManager.getPlugin().getServer().getScheduler().runTaskTimer(
                gameManager.getPlugin(), sender, intervalTicks, intervalTicks);
    }

    public Player getPlayer() {
        return player;
    }

    public GameConfig getConfig() {
        return config;
    }

    public Block getTarget() {
        return target;
    }

    public Runnable getOnSuccess() {
        return onSuccess;
    }

    public Location getStartLocation() {
        return startLocation;
    }
}
