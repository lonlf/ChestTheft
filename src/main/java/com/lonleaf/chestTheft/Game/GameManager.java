package com.lonleaf.chestTheft.Game;

import com.lonleaf.chestTheft.ChestTheft;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.UUID;

// 游戏管理器
public class GameManager {
    public final HashMap<UUID, GameSession> activeGames = new HashMap<>();
    final ChestTheft plugin;

    public GameManager(ChestTheft plugin) {
        this.plugin = plugin;
    }

    public void startGame(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeGames.containsKey(uuid)) {
            return;
        }

        GameSession session = new GameSession(player, this);
        activeGames.put(uuid, session);
        session.start();
    }

    public void endGame(Player player) {
        GameSession session = activeGames.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
        }
    }

    public boolean isPlaying(Player player) {
        return activeGames.containsKey(player.getUniqueId());
    }

    public void cleanup() {
        activeGames.values().forEach(GameSession::stop);
        activeGames.clear();
    }
}
