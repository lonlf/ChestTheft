package com.lonleaf.chesttheft.minigame.tumblerbar;

import com.lonleaf.chesttheft.minigame.MiniGame;
import com.lonleaf.chesttheft.minigame.MiniGameContext;
import com.lonleaf.chesttheft.minigame.MiniGameSession;

/** 机关转轮小游戏定义（tumbler-bar），规则见 TumblerBarSession。 */
public class TumblerBarMiniGame implements MiniGame {

    @Override
    public String getType() {
        return "tumbler-bar";
    }

    @Override
    public MiniGameSession createSession(MiniGameContext context) {
        return new TumblerBarSession(context);
    }
}
