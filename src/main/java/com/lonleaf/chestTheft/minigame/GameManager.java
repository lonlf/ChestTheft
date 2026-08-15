package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GameManager {
    private final JavaPlugin plugin;
    /** 默认小游戏配置（来自配置文件 game 小节）：未指定特定配置时使用。 */
    // volatile：reload 在主线程更新，事件监听器 / 定时任务线程读取
    private volatile GameConfig defaultGameConfig;
    private final Map<UUID, GameSession> activeGames = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** 撬锁成功后的开箱授权：玩家 → (箱子位置 → 授权记录)。 */
    private final Map<UUID, Map<BlockLocation, AccessGrant>> grantedAccess = new HashMap<>();

    public GameManager(JavaPlugin plugin, GameConfig defaultGameConfig) {
        this.plugin = plugin;
        this.defaultGameConfig = defaultGameConfig;
    }

    /** reload 时更新默认配置（后续新会话使用新配置，进行中的会话保持旧配置）。 */
    public void updateConfig(GameConfig defaultGameConfig) {
        this.defaultGameConfig = defaultGameConfig;
    }

    /** 尝试开始撬锁（使用默认配置），成功返回 true，冷却中或已在进行时返回 false。 */
    public boolean startGame(Player player, Block target) {
        return startGame(player, target, null);
    }

    /** 尝试开始撬锁（使用指定配置，为 null 时回退默认配置）。 */
    public boolean startGame(Player player, Block target, GameConfig gameConfig) {
        UUID uuid = player.getUniqueId();
        if (activeGames.containsKey(uuid)) {
            return false;
        }
        GameConfig config = gameConfig != null ? gameConfig : defaultGameConfig;

        long now = System.currentTimeMillis();
        Long lastEnd = cooldowns.get(uuid);
        if (lastEnd != null) {
            long remaining = (lastEnd + config.getCooldownSeconds() * 1000L - now) / 1000L + 1;
            if (remaining > 0) {
                Messages.send(player, Messages.COOLDOWN, Messages.COOLDOWN_FORMAT, remaining);
                return false;
            }
        }

        GameSession session = new GameSession(player, this, config, target);
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

    /** 授予玩家对指定箱子的开箱授权：access-duration > 0 时限时多次；= 0 时仅一次打开机会。 */
    public void grantAccess(Player player, BlockLocation location) {
        int duration = defaultGameConfig.getAccessDurationSeconds();
        long windowMs;
        boolean once;
        if (duration > 0) {
            windowMs = duration * 1000L;
            once = false;
        } else {
            // 一次性授权：仅一次打开机会，在 access-once-window 内有效
            windowMs = defaultGameConfig.getAccessOnceWindowSeconds() * 1000L;
            once = true;
        }
        grantedAccess.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(location, new AccessGrant(System.currentTimeMillis() + windowMs, once));
    }

    /** 玩家是否持有该箱子的有效开箱授权（过期自动失效）。 */
    public boolean hasAccess(Player player, BlockLocation location) {
        Map<BlockLocation, AccessGrant> grants = grantedAccess.get(player.getUniqueId());
        if (grants == null) {
            return false;
        }
        AccessGrant grant = grants.get(location);
        if (grant == null) {
            return false;
        }
        if (System.currentTimeMillis() >= grant.expireAt) {
            grants.remove(location);
            if (grants.isEmpty()) {
                grantedAccess.remove(player.getUniqueId());
            }
            return false;
        }
        return true;
    }

    /** 消费一次性授权（打开箱子后调用）；限时授权不受影响。 */
    public void consumeOnceAccess(Player player, BlockLocation location) {
        Map<BlockLocation, AccessGrant> grants = grantedAccess.get(player.getUniqueId());
        if (grants == null) {
            return;
        }
        AccessGrant grant = grants.get(location);
        if (grant != null && grant.once) {
            grants.remove(location);
            if (grants.isEmpty()) {
                grantedAccess.remove(player.getUniqueId());
            }
        }
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
        grantedAccess.clear();
    }

    /** 开箱授权记录：expireAt 为过期时间戳，once 为 true 时仅可打开一次（打开后消耗）。 */
    private static final class AccessGrant {
        final long expireAt;
        final boolean once;

        AccessGrant(long expireAt, boolean once) {
            this.expireAt = expireAt;
            this.once = once;
        }
    }
}
