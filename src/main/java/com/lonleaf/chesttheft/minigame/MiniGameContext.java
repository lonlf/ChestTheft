package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** 小游戏会话创建上下文：携带单局所需的公共参数与所属小游戏配置。 */
public final class MiniGameContext {

    private final Player player;
    private final GameManager gameManager;
    /** 撬锁目标箱子：判定成功后打开此箱子。 */
    private final Block target;
    /** 撬锁成功回调（战利品箱等自定义目标）：不为 null 时成功后执行回调而非打开原版箱子。 */
    private final Runnable onSuccess;
    /** 开始撬锁时的玩家位置：移动超范围中断判定基准。 */
    private final Location startLocation;
    /** 该局公共小游戏配置：玩法特有参数由各玩法从 config.getSection() 自行解析。 */
    private final GameConfig config;

    public MiniGameContext(Player player, GameManager gameManager, Block target, Runnable onSuccess, GameConfig config) {
        this.player = player;
        this.gameManager = gameManager;
        this.target = target;
        this.onSuccess = onSuccess;
        this.config = config;
        this.startLocation = player.getLocation().clone();
    }

    public Player getPlayer() {
        return player;
    }

    public GameManager getGameManager() {
        return gameManager;
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

    public GameConfig getConfig() {
        return config;
    }
}
