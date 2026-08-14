package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GameManager {
    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Map<UUID, GameSession> activeGames = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public GameManager(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** 尝试开始撬锁，成功返回 true，冷却中或已在进行时返回 false。 */
    public boolean startGame(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeGames.containsKey(uuid)) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long lastEnd = cooldowns.get(uuid);
        if (lastEnd != null) {
            long remaining = (lastEnd + config.getCooldownSeconds() * 1000L - now) / 1000L + 1;
            if (remaining > 0) {
                Messages.send(player, Messages.COOLDOWN, Messages.COOLDOWN_FORMAT, remaining);
                return false;
            }
        }

        GameSession session = new GameSession(player, this, config);
        activeGames.put(uuid, session);
        session.start();
        return true;
    }

    public void endGame(Player player) {
        GameSession session = activeGames.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    public boolean isPlaying(Player player) {
        return activeGames.containsKey(player.getUniqueId());
    }

    public GameSession getSession(Player player) {
        return activeGames.get(player.getUniqueId());
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public void cleanup() {
        activeGames.values().forEach(GameSession::stop);
        activeGames.clear();
    }
}
