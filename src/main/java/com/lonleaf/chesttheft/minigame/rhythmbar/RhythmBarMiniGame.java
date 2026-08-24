package com.lonleaf.chesttheft.minigame.rhythmbar;

import com.lonleaf.chesttheft.minigame.MiniGame;
import com.lonleaf.chesttheft.minigame.MiniGameContext;
import com.lonleaf.chesttheft.minigame.MiniGameSession;

/**
 * 节奏条小游戏定义：类型 id = rhythm-bar，规则见 RhythmBarSession。
 */
public class RhythmBarMiniGame implements MiniGame {

    /** 小游戏类型标识（配置 game-type 使用）。 */
    public static final String TYPE = "rhythm-bar";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public MiniGameSession createSession(MiniGameContext context) {
        return new RhythmBarSession(context);
    }
}
