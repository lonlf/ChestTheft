package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
import com.lonleaf.chesttheft.trigger.TriggerContext;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import com.lonleaf.chesttheft.trigger.TriggerType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GameManager implements Listener {
    private final JavaPlugin plugin;
    private final TriggerManager triggerManager;
    private final ChestService chestService;
    private final ItemManager itemManager;
    /** 默认小游戏配置（来自配置文件 game 小节）：未指定特定配置时使用。 */
    // volatile：reload 在主线程更新，事件监听器 / 定时任务线程读取
    private volatile GameConfig defaultGameConfig;
    private final Map<UUID, GameSession> activeGames = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** 撬锁成功后的开箱授权：玩家 → (箱子位置 → 授权记录)。 */
    private final Map<UUID, Map<BlockLocation, AccessGrant>> grantedAccess = new HashMap<>();

    public GameManager(JavaPlugin plugin, GameConfig defaultGameConfig, TriggerManager triggerManager,
                       ChestService chestService, ItemManager itemManager) {
        this.plugin = plugin;
        this.defaultGameConfig = defaultGameConfig;
        this.triggerManager = triggerManager;
        this.chestService = chestService;
        this.itemManager = itemManager;
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
        return startGame(player, target, gameConfig, null);
    }

    /** 尝试开始撬锁（使用指定配置并携带成功回调；回调为 null 时成功后按原版逻辑打开目标箱子）。 */
    public boolean startGame(Player player, Block target, GameConfig gameConfig, Runnable onSuccess) {
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

        GameSession session = new GameSession(player, this, config, target, onSuccess);
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

    /** 撤销指定箱子的全部开箱授权（箱子所有者打开箱子后调用，实现"重新上锁"）。 */
    public void revokeAllAccess(BlockLocation location) {
        grantedAccess.values().forEach(grants -> grants.remove(location));
        grantedAccess.values().removeIf(Map::isEmpty);
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

    /** 撬锁中受到伤害：按当前会话配置决定是否中断（伤害被取消时不视为受击）。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        GameSession session = activeGames.get(player.getUniqueId());
        if (session != null && session.getConfig().isInterruptDamage()) {
            interrupt(session, player);
        }
    }

    /** 撬锁中移动超过配置范围：按当前会话配置决定是否中断。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameSession session = activeGames.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        double range = session.getConfig().getInterruptMoveRange();
        if (range > 0 && session.getStartLocation().distance(event.getTo()) > range) {
            interrupt(session, player);
        }
    }

    private void interrupt(GameSession session, Player player) {
        Messages.send(player, Messages.PICK_INTERRUPTED, Messages.PICK_INTERRUPTED_FORMAT);
        BlockLocation location = BlockLocation.from(session.getTarget());
        triggerManager.fire(TriggerType.INTERRUPTED, new TriggerContext(player, location));
        fireLockTrigger(session.getTarget(), player);
        endGame(player);
    }

    /** 触发目标锁物品自带的打断触发器；锁未配置时不处理。 */
    private void fireLockTrigger(Block target, Player player) {
        if (target == null) {
            return;
        }
        ItemStack lockItem = chestService.getLockItem(target);
        if (lockItem == null) {
            return;
        }
        String triggerData = itemManager.getLockTrigger(lockItem);
        if (triggerData != null) {
            triggerManager.fireForLock(triggerData, TriggerType.INTERRUPTED,
                    new TriggerContext(player, BlockLocation.from(target)));
        }
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
