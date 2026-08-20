package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 小游戏会话抽象基类：封装单局公共生命周期（调度循环、超时、结束），玩法规则由子类实现。
 */
public abstract class MiniGameSession {

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

    /** 启动会话：注册定时任务并进入玩法循环。 */
    public final void start() {
        onStart();
        task = gameManager.getPlugin().getServer().getScheduler().runTaskTimer(
                gameManager.getPlugin(), this::update, 0, getTickInterval());
    }

    private void update() {
        if (System.currentTimeMillis() >= deadline) {
            Messages.send(player, Messages.GAME_TIMEOUT, Messages.GAME_TIMEOUT_FORMAT);
            gameManager.endGame(player);
            return;
        }
        onTick();
    }

    /** 结束会话：取消定时任务并调用清理钩子（结束提示由调用方以标题覆盖，此处不清空标题）。 */
    public final void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        onStop();
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
