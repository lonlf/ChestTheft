package com.lonleaf.chesttheft.minigame.game.movingbar;

import com.lonleaf.chesttheft.minigame.MiniGame;
import com.lonleaf.chesttheft.minigame.MiniGameContext;
import com.lonleaf.chesttheft.minigame.MiniGameSession;

/**
 * 移动游标小游戏定义：类型 id = moving-bar，规则见 MovingBarSession。
 */
public class MovingBarMiniGame implements MiniGame {

    /** 小游戏类型标识（配置 game-type 使用）。 */
    public static final String TYPE = "moving-bar";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public MiniGameSession createSession(MiniGameContext context) {
        return new MovingBarSession(context);
    }
}
