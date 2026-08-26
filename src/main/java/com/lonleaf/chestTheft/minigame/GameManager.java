package com.lonleaf.chesttheft.minigame;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.event.ChestOpenEvent;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.lonleaf.chesttheft.service.ChestService;
import com.lonleaf.chesttheft.trigger.TriggerContext;
import com.lonleaf.chesttheft.trigger.TriggerManager;
import com.lonleaf.chesttheft.trigger.TriggerType;
import com.lonleaf.chesttheft.minigame.movingbar.MovingBarMiniGame;
import com.lonleaf.chesttheft.minigame.rhythmbar.RhythmBarMiniGame;
import com.lonleaf.chesttheft.minigame.tumblerbar.TumblerBarMiniGame;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GameManager implements Listener {
    private final JavaPlugin plugin;
    private final TriggerManager triggerManager;
    private final ChestService chestService;
    private final ItemManager itemManager;
    /** 小游戏类型注册表：配置 game-type 指定玩法，可注册扩展玩法。 */
    private final Map<String, MiniGame> miniGames = new HashMap<>();
    /** 默认小游戏配置（来自配置文件 game 小节）：未指定特定配置时使用。 */
    // volatile：reload 在主线程更新，事件监听器 / 定时任务线程读取
    private volatile GameConfig defaultGameConfig;
    private final Map<UUID, MiniGameSession> activeGames = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** 撬锁成功后的全局解锁状态：锁位置 → 解锁记录；一人撬锁后所有玩家在有效期内均可打开，过期自动失效。 */
    private final Map<BlockLocation, GlobalUnlock> unlockedLocks = new HashMap<>();

    public GameManager(JavaPlugin plugin, GameConfig defaultGameConfig, TriggerManager triggerManager,
                       ChestService chestService, ItemManager itemManager) {
        this.plugin = plugin;
        this.defaultGameConfig = defaultGameConfig;
        this.triggerManager = triggerManager;
        this.chestService = chestService;
        this.itemManager = itemManager;
        // 内置小游戏：移动游标（moving-bar）、节奏条（rhythm-bar）、机关转轮（tumbler-bar）；其他玩法通过 registerMiniGame 注册扩展
        registerMiniGame(new MovingBarMiniGame());
        registerMiniGame(new RhythmBarMiniGame());
        registerMiniGame(new TumblerBarMiniGame());
    }

    /** 注册小游戏类型：已存在时以新定义覆盖。 */
    public void registerMiniGame(MiniGame miniGame) {
        miniGames.put(miniGame.getType(), miniGame);
    }

    /** 注销小游戏类型：注销后配置引用该类型将无法开局。 */
    public void unregisterMiniGame(String type) {
        miniGames.remove(type);
    }

    /** 按类型获取已注册的小游戏，未注册返回 null。 */
    public MiniGame getMiniGame(String type) {
        return miniGames.get(type);
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
            // 冷却已过期：顺手清理，避免过期条目长期驻留内存
            cooldowns.remove(uuid);
        }

        MiniGame miniGame = miniGames.get(config.getGameType());
        if (miniGame == null) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_GAME_TYPE_UNKNOWN,
                    config.getGameType(), miniGames.keySet()));
            return false;
        }
        MiniGameSession session = miniGame.createSession(
                new MiniGameContext(player, this, target, onSuccess, config));
        activeGames.put(uuid, session);
        session.start();
        return true;
    }

    public void endGame(Player player) {
        MiniGameSession session = activeGames.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** 玩家退出：清理其会话、冷却与开箱授权缓存，避免内存泄漏（不进入冷却，无消息提示）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        MiniGameSession session = activeGames.remove(uuid);
        if (session != null) {
            session.stop();
        }
        cooldowns.remove(uuid);
    }

    /** 会话主动失败结束（如节奏条光标越过判定点未命中）：发送失败消息、触发失败触发器并结束会话。 */
    public void failGame(MiniGameSession session) {
        Player player = session.getPlayer();
        Messages.send(player, Messages.FAIL, Messages.FAIL_FORMAT);
        session.getConfig().getFailSound().play(player);
        triggerManager.fire(TriggerType.FAIL, new TriggerContext(player, BlockLocation.from(session.getTarget())));
        fireLockTrigger(session.getTarget(), TriggerType.FAIL, player);
        endGame(player);
    }

    /** 会话主动成功结束（按键类玩法满足条件时调用）：发送成功消息、触发成功触发器，
     *  战利品箱执行回调或授予开箱授权（成功后由玩家自行右键打开）并结束会话。 */
    public void successGame(MiniGameSession session) {
        Player player = session.getPlayer();
        Messages.send(player, Messages.SUCCESS, Messages.SUCCESS_FORMAT);
        session.getConfig().getSuccessSound().play(player);
        triggerManager.fire(TriggerType.SUCCESS, new TriggerContext(player, BlockLocation.from(session.getTarget())));
        fireLockTrigger(session.getTarget(), TriggerType.SUCCESS, player);
        // 战利品箱等自定义目标：成功后执行回调打开；普通上锁方块（箱子/陷阱箱/门等）不再自动打开，
        // 改为授予开箱授权（access-duration 限时多次 / access-once-window 一次性），由玩家自行右键打开
        Runnable onSuccess = session.getOnSuccess();
        if (onSuccess != null) {
            onSuccess.run();
        } else {
            Block target = session.getTarget();
            if (target != null && !target.getType().isAir()) {
                // 使用本局配置（按锁等级选取）：等级配置的 access-duration / access-once-window 才能生效。
                // 全局解锁：一人撬锁后所有玩家均可打开（无需再撬锁），过期自动重新上锁
                unlockGlobally(BlockLocation.from(target), session.getConfig());
                // 预授权外部保护（Residence/Dominion/Bolt/LWC 等）：撬锁者随后右键打开时，
                // 保护插件（LOWEST 先于本插件注册的 Dominion 等）的交互检查已放行，
                // 不再发送"无权限"提示；该授权在关闭/退出时撤销。无本插件的解锁状态
                // （isLockPicked）时打开流程本身被 ChestTheft 拦截，故无越权风险
                Bukkit.getPluginManager().callEvent(
                        new ChestOpenEvent(player, target, BlockLocation.from(target)));
            }
        }
        endGame(player);
    }

    public boolean isPlaying(Player player) {
        return activeGames.containsKey(player.getUniqueId());
    }

    /** 授予指定锁的全局解锁状态（使用本局配置，null 回退默认配置）：
     *  access-duration > 0 时限时解锁；= 0 时仅一次打开机会（任意玩家第一次打开后重新上锁）。 */
    public void unlockGlobally(BlockLocation location, GameConfig gameConfig) {
        GameConfig cfg = gameConfig != null ? gameConfig : defaultGameConfig;
        int duration = cfg.getAccessDurationSeconds();
        long windowMs;
        boolean once;
        if (duration > 0) {
            windowMs = duration * 1000L;
            once = false;
        } else {
            // 一次性解锁：仅一次打开机会，在 access-once-window 内有效
            windowMs = cfg.getAccessOnceWindowSeconds() * 1000L;
            once = true;
        }
        unlockedLocks.put(location, new GlobalUnlock(System.currentTimeMillis() + windowMs, once));
    }

    /** 该锁是否处于被撬开的全局解锁状态（任意玩家均可打开；过期自动失效并重新上锁）。 */
    public boolean isLockPicked(BlockLocation location) {
        GlobalUnlock unlock = unlockedLocks.get(location);
        if (unlock == null) {
            return false;
        }
        if (System.currentTimeMillis() >= unlock.expireAt) {
            unlockedLocks.remove(location);
            return false;
        }
        return true;
    }

    /** 撤销指定锁的全局解锁状态（锁所有者打开箱子后调用，实现"重新上锁"）。 */
    public void revokeAllAccess(BlockLocation location) {
        unlockedLocks.remove(location);
    }

    /** 消费一次性解锁（任意玩家打开箱子后调用，仅首次打开生效）：一次性解锁立即重新上锁；限时解锁不受影响。 */
    public void consumeOnceAccess(BlockLocation location) {
        GlobalUnlock unlock = unlockedLocks.get(location);
        if (unlock != null && unlock.once) {
            unlockedLocks.remove(location);
        }
    }

    public MiniGameSession getSession(Player player) {
        return activeGames.get(player.getUniqueId());
    }

    /** 撬锁中受到伤害：按当前会话配置决定是否中断（伤害被取消时不视为受击）。 */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        MiniGameSession session = activeGames.get(player.getUniqueId());
        if (session != null && session.getConfig().isInterruptDamage()) {
            interrupt(session, player);
        }
    }

    /** 撬锁中移动超过配置范围：按当前会话配置决定是否中断。 */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        MiniGameSession session = activeGames.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        double range = session.getConfig().getInterruptMoveRange();
        if (range > 0 && session.getStartLocation().distance(event.getTo()) > range) {
            interrupt(session, player);
        }
    }

    private void interrupt(MiniGameSession session, Player player) {
        Messages.send(player, Messages.PICK_INTERRUPTED, Messages.PICK_INTERRUPTED_FORMAT);
        BlockLocation location = BlockLocation.from(session.getTarget());
        triggerManager.fire(TriggerType.INTERRUPTED, new TriggerContext(player, location));
        fireLockTrigger(session.getTarget(), TriggerType.INTERRUPTED, player);
        endGame(player);
    }

    /** 撬锁中死亡：结束会话（受击中断已按配置处理，死亡再兜底一次，endGame 幂等）。 */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        endIfPlaying(event.getEntity());
    }

    /** 撬锁中传送（/tp、传送门等不触发 PlayerMoveEvent）：结束会话，清理坐骑实体。 */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        endIfPlaying(event.getPlayer());
    }

    /** 撬锁中换世界：结束会话，清理坐骑实体。 */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        endIfPlaying(event.getPlayer());
    }

    private void endIfPlaying(Player player) {
        MiniGameSession session = activeGames.get(player.getUniqueId());
        if (session != null) {
            interrupt(session, player);
        }
    }

    /** 触发目标锁物品自带的触发器；锁未配置时不处理。 */
    private void fireLockTrigger(Block target, TriggerType type, Player player) {
        if (target == null) {
            return;
        }
        ItemStack lockItem = chestService.getLockItem(target);
        if (lockItem == null) {
            return;
        }
        List<String> triggerIds = triggerManager.parseTriggerIds(itemManager.getLockTrigger(lockItem));
        if (triggerIds != null) {
            triggerManager.fireForLock(triggerIds, type,
                    new TriggerContext(player, BlockLocation.from(target)));
        }
    }

    /** 触发目标锁物品自带的打断触发器；锁未配置时不处理。 */
    private void fireLockTrigger(Block target, Player player) {
        fireLockTrigger(target, TriggerType.INTERRUPTED, player);
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public void cleanup() {
        activeGames.values().forEach(MiniGameSession::stop);
        activeGames.clear();
        unlockedLocks.clear();
    }

    /** 全局解锁记录：expireAt 为过期时间戳，once 为 true 时仅可打开一次（任意玩家首次打开后重新上锁）。 */
    private static final class GlobalUnlock {
        final long expireAt;
        final boolean once;

        GlobalUnlock(long expireAt, boolean once) {
            this.expireAt = expireAt;
            this.once = once;
        }
    }
}
