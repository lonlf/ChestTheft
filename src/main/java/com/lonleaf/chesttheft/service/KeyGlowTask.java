package com.lonleaf.chesttheft.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.lootchest.LootChestDisplay;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 钥匙发光提示：玩家手持与某把上锁箱子配对值相等的钥匙时，周期性在箱子位置播放彩色粒子
 * （受 key.particle-enabled / particle-color / particle-radius 控制），
 * 并叠加一个发光展示实体（BlockDisplay，与锁方块同材质且朝向一致、视觉无缝，glow 轮廓为 key.glow-color；
 * 受 key.glow-enabled / glow-color / glow-radius 控制）使其醒目；粒子与发光可独立开关。
 * 箱子打开时发光实体被移除（避免开盖动画错位），关闭后恢复。
 * 判定与开锁一致（位置配对 + 凭证一致）。
 */
public class KeyGlowTask extends BukkitRunnable {

    private static final long INTERVAL_TICKS = 5L;
    private static final int PARTICLE_COUNT = 8;
    private static final double PARTICLE_OFFSET = 0.45;
    private static final float DUST_SIZE = 1.0f;
    /** 粒子可见距离（对应原版 spawnParticle 默认 64 格）。 */
    private static final double NEARBY_DISTANCE_SQ = 64.0 * 64.0;
    /** 发光轮廓透明度（glow color override 的 alpha，半透明）。 */
    private static final int GLOW_ALPHA = 0x99;

    private final Plugin plugin;
    private final PluginConfig config;
    private final ChestService chestService;
    private final ItemManager itemManager;
    /** 当前处于发光状态的箱子位置 → 发光展示实体 ID（每 5 tick 与匹配结果对账）。 */
    private final Map<BlockLocation, Integer> glowEntities = new HashMap<>();
    /** 打开中的箱子位置：打开时移除发光实体（避免原版开盖动画与静态展示实体错位），关闭后延迟恢复。 */
    private final Set<BlockLocation> openChests = new HashSet<>();
    /** 关闭后延迟恢复发光的调度任务（按位置登记，延迟期间重新打开则取消，避免误恢复）。 */
    private final Map<BlockLocation, BukkitTask> pendingRestore = new HashMap<>();
    /** 关闭后延迟恢复发光的时长（0.5 秒），避免关闭动画期间发光实体立即叠加造成闪烁/错位。 */
    private static final long RESTORE_DELAY_TICKS = 10L;

    public KeyGlowTask(Plugin plugin, PluginConfig config, ChestService chestService, ItemManager itemManager) {
        this.plugin = plugin;
        this.config = config;
        this.chestService = chestService;
        this.itemManager = itemManager;
    }

    public void start() {
        runTaskTimer(plugin, 0L, INTERVAL_TICKS);
    }

    @Override
    public void run() {
        boolean particleEnabled = config.isKeyParticleEnabled();
        boolean glowEnabled = config.isKeyGlowEnabled();
        if (!particleEnabled && !glowEnabled) {
            removeAllGlowEntities();
            return;
        }
        Color particleColor = config.getKeyParticleColor();
        double particleRadiusSq = config.getKeyParticleRadius() * config.getKeyParticleRadius();
        Color glowColor = config.getKeyGlowColor();
        double glowRadiusSq = config.getKeyGlowRadius() * config.getKeyGlowRadius();
        Set<BlockLocation> glowMatched = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!itemManager.isType(hand, ItemType.KEY)) {
                continue;
            }
            BlockLocation paired = itemManager.getPairedLock(hand);
            if (paired == null || !tokenMatches(paired, hand)) {
                continue;
            }
            World world = paired.toWorld();
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(paired.getX(), paired.getY(), paired.getZ());
            if (block.getType().isAir()) {
                continue;
            }
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            double distanceSq = player.getLocation().distanceSquared(center);
            // 粒子效果（key.particle-*）：仅播放粒子，不改变发光状态
            if (particleEnabled && distanceSq <= particleRadiusSq) {
                com.github.retrooper.packetevents.protocol.particle.Particle<?> dust =
                        new com.github.retrooper.packetevents.protocol.particle.Particle<>(
                                ParticleTypes.DUST,
                                new ParticleDustData(DUST_SIZE,
                                        new com.github.retrooper.packetevents.protocol.color.Color(
                                                particleColor.getRed(), particleColor.getGreen(), particleColor.getBlue())));
                WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(
                        dust, false,
                        new Vector3d(center.getX(), center.getY(), center.getZ()),
                        new com.github.retrooper.packetevents.util.Vector3f(
                                (float) PARTICLE_OFFSET, (float) PARTICLE_OFFSET, (float) PARTICLE_OFFSET),
                        0f, PARTICLE_COUNT);
                for (Player viewer : world.getPlayers()) {
                    if (viewer.getLocation().distanceSquared(center) <= NEARBY_DISTANCE_SQ) {
                        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
                    }
                }
            }
            // 发光效果（key.glow-*）：同一位置多把钥匙只计一次（发光实体按位置去重）
            if (glowEnabled && distanceSq <= glowRadiusSq) {
                glowMatched.add(paired);
            }
        }
        // 对账发光展示实体：仍匹配的位置保持/新建，不再匹配的位置移除
        Iterator<Map.Entry<BlockLocation, Integer>> iterator = glowEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockLocation, Integer> entry = iterator.next();
            if (!glowMatched.contains(entry.getKey())) {
                LootChestDisplay.destroyEntity(entry.getValue());
                iterator.remove();
            }
        }
        for (BlockLocation loc : glowMatched) {
            if (openChests.contains(loc) || glowEntities.containsKey(loc)) {
                continue;
            }
            World world = loc.toWorld();
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            int entityId = LootChestDisplay.spawnGlowDisplay(block, glowArgb(glowColor));
            glowEntities.put(loc, entityId);
        }
    }

    /** 箱子打开时调用：取消未完成的延迟恢复，若该位置正在发光则移除发光实体并标记打开中。 */
    public void onChestOpen(BlockLocation loc) {
        BukkitTask task = pendingRestore.remove(loc);
        if (task != null) {
            task.cancel();
        }
        Integer entityId = glowEntities.remove(loc);
        if (entityId != null) {
            LootChestDisplay.destroyEntity(entityId);
            openChests.add(loc);
        }
    }

    /** 箱子关闭时调用：延迟 0.5 秒恢复发光（若玩家仍持有匹配钥匙，下个对账周期自动重新生成）。 */
    public void onChestClose(BlockLocation loc) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingRestore.remove(loc);
            openChests.remove(loc);
        }, RESTORE_DELAY_TICKS);
        pendingRestore.put(loc, task);
    }

    /** key.glow-color（RGB）→ glow color override（ARGB，alpha 半透明）。 */
    private static int glowArgb(Color color) {
        return (GLOW_ALPHA << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    /** 移除全部发光展示实体并清空登记（关闭发光/取消任务时调用）。 */
    private void removeAllGlowEntities() {
        if (glowEntities.isEmpty()) {
            return;
        }
        int[] entityIds = new int[glowEntities.size()];
        int index = 0;
        for (int entityId : glowEntities.values()) {
            entityIds[index++] = entityId;
        }
        glowEntities.clear();
        LootChestDisplay.destroyEntity(entityIds);
    }

    @Override
    public void cancel() {
        removeAllGlowEntities();
        pendingRestore.values().forEach(BukkitTask::cancel);
        pendingRestore.clear();
        openChests.clear();
        super.cancel();
    }

    /** 钥匙凭证与锁当前凭证一致（配对值相等），且该锁仍存在。 */
    private boolean tokenMatches(BlockLocation paired, ItemStack key) {
        String token = itemManager.getPairedToken(key);
        if (token == null) {
            return false;
        }
        World world = paired.toWorld();
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(paired.getX(), paired.getY(), paired.getZ());
        String current = chestService.getLockToken(block);
        return token.equals(current);
    }
}
