package com.lonleaf.chesttheft.minigame;

/**
 * 小游戏定义：类型标识 + 会话工厂。每种玩法实现一个 MiniGame 并注册到 GameManager。
 */
public interface MiniGame {

    /** 小游戏类型唯一标识（配置 game-type 使用）。 */
    String getType();

    /** 为指定玩家创建一局新会话（每局调用一次）。 */
    MiniGameSession createSession(MiniGameContext context);
}
