package com.lonleaf.chesttheft.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.display.DisplayEntityUtil;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 钥匙发光提示：手持匹配钥匙时周期播放粒子并叠加发光展示实体（仅持有者可见），开盖时移除、关闭后恢复。 */
public class KeyGlowTask extends BukkitRunnable {

    private static final long INTERVAL_TICKS = 5L;
    private static final int PARTICLE_COUNT = 8;
    private static final double PARTICLE_OFFSET = 0.45;
    private static final float DUST_SIZE = 1.0f;
    /** 发光轮廓透明度（glow color override 的 alpha，半透明）。 */
    private static final int GLOW_ALPHA = 0x99;

    private final Plugin plugin;
    private final PluginConfig config;
    private final ChestService chestService;
    private final ItemManager itemManager;
    /** 当前处于发光状态的箱子位置 → 发光展示实体 ID 数组（双箱为左右两个实体，单箱为单个），每 5 tick 与匹配结果对账。 */
    private final Map<BlockLocation, int[]> glowEntities = new HashMap<>();
    /** 方块位置 → 发光 key：双箱两侧统一指向双箱 key（东北角块），单箱指向自身；每次对账重建。 */
    private final Map<BlockLocation, BlockLocation> sideToGlowKey = new HashMap<>();
    /** 最近一次对账的匹配结果：发光 key → 可见观众；延迟重生任务据此判断玩家是否仍持有匹配钥匙。 */
    private final Map<BlockLocation, Set<UUID>> glowMatched = new HashMap<>();
    /** 门发光实体已同步的开门状态（仅门位置登记）：open 变化时销毁实体等待重生（生成时只复制一次，门转动后需跟随）。 */
    private final Map<BlockLocation, Boolean> doorGlowOpen = new HashMap<>();
    /** 门 open 变化后延迟重生的调度任务（按位置登记）：销毁旧实体等转动动画播完再生成新状态实体，避免静态实体与动画错位。 */
    private final Map<BlockLocation, BukkitTask> pendingDoorSync = new HashMap<>();
    /** 门 open 状态变化后延迟重生的时长（5 tick），等待客户端门转动动画播完。 */
    private static final long DOOR_SYNC_DELAY_TICKS = 5L;
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
        // 匹配结果：发光 key（单箱=自身 / 双箱=统一 key）→ 可见观众（持有匹配钥匙且在发光范围内的玩家）
        glowMatched.clear();
        sideToGlowKey.clear();
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
            List<Block> sides = chestSides(block);
            boolean isDouble = sides.size() > 1;
            // 双箱统一 key：东北角块（x/z 和较大的那侧），从任一侧进入保持一致
            BlockLocation doubleKey = null;
            if (isDouble) {
                Block keyBlock = sides.get(0).getX() + sides.get(0).getZ()
                        > sides.get(1).getX() + sides.get(1).getZ() ? sides.get(0) : sides.get(1);
                doubleKey = BlockLocation.from(keyBlock);
            }
            // 双箱子：钥匙配对锁位于单侧，粒子需覆盖双箱两侧；发光合并为一个实体
            for (Block side : sides) {
                Location center = side.getLocation().add(0.5, 0.5, 0.5);
                double distanceSq = player.getLocation().distanceSquared(center);
                // 粒子效果（key.particle-*）：仅对钥匙持有者发送
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
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, wrapper);
                }
                // 发光效果（key.glow-*）：双箱统一 key，单箱用自身位置；任一侧在范围内即发光
                if (glowEnabled && distanceSq <= glowRadiusSq) {
                    BlockLocation key = isDouble ? doubleKey : BlockLocation.from(side);
                    sideToGlowKey.put(BlockLocation.from(side), key);
                    glowMatched.computeIfAbsent(key, k -> new HashSet<>()).add(player.getUniqueId());
                }
            }
        }
        // 对账发光展示实体：仍匹配的位置同步观众集合，不再匹配的位置移除
        Iterator<Map.Entry<BlockLocation, int[]>> iterator = glowEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockLocation, int[]> entry = iterator.next();
            Set<UUID> holders = glowMatched.get(entry.getKey());
            if (holders == null) {
                DisplayEntityUtil.destroyEntity(entry.getValue());
                doorGlowOpen.remove(entry.getKey());
                iterator.remove();
            } else {
                DisplayEntityUtil.syncGlowViewers(entry.getValue(), holders);
                // 门 open 状态变化时延迟刷新展示实体方块状态，使其跟随真实门转动
                syncDoorGlowState(entry.getKey());
            }
        }
        for (BlockLocation loc : glowMatched.keySet()) {
            // 打开中 / 已存在实体 / 门转动等待重生（销毁后 5 tick 内）的位置跳过，不重复生成
            if (openChests.contains(loc) || glowEntities.containsKey(loc) || pendingDoorSync.containsKey(loc)) {
                continue;
            }
            spawnGlowEntities(loc, glowMatched.get(loc), glowColor);
        }
    }

    /** 按位置生成发光展示实体并登记（门/双箱/单箱按真实方块结构生成；生成时复制最新方块状态）。 */
    private void spawnGlowEntities(BlockLocation loc, Set<UUID> holders, Color glowColor) {
        World world = loc.toWorld();
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
        List<Block> sides = chestSides(block);
        int[] entityIds;
        if (sides.size() > 1) {
            if (isDoorMaterial(block.getType())) {
                // 门：上下两个真实半块状态（half=upper/lower）的展示实体并排，覆盖整扇门
                entityIds = DisplayEntityUtil.spawnGlowDisplayDoor(block, sides.get(1), glowArgb(glowColor), holders);
            } else {
                // 双箱：左右两个真实方块状态（type=left/right）的展示实体并排，还原双箱外观
                entityIds = DisplayEntityUtil.spawnGlowDisplayDouble(block, sides.get(1), glowArgb(glowColor), holders);
            }
        } else {
            entityIds = new int[]{DisplayEntityUtil.spawnGlowDisplay(block, glowArgb(glowColor), holders)};
        }
        glowEntities.put(loc, entityIds);
    }

    /** 门 open 状态变化时：立即销毁旧发光实体，5 tick 后按最新门状态重新生成（转动动画期间无实体，避免静态实体与动画错位）；非门或无变化跳过。 */
    private void syncDoorGlowState(BlockLocation loc) {
        World world = loc.toWorld();
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
        if (!isDoorMaterial(block.getType())) {
            return;
        }
        if (!(block.getState().getBlockData() instanceof org.bukkit.block.data.type.Door doorData)) {
            return;
        }
        Boolean lastOpen = doorGlowOpen.get(loc);
        if (lastOpen == null) {
            // 首次登记（实体刚生成）：只记录当前 open 状态，不视为变化——
            // 否则生成后的下一轮对账会被误判为"转动"而立即销毁重生，造成从范围外进入时闪烁
            doorGlowOpen.put(loc, doorData.isOpen());
            return;
        }
        if (lastOpen == doorData.isOpen()) {
            return;
        }
        doorGlowOpen.put(loc, doorData.isOpen());
        // 立即销毁旧实体（此时门转动动画开始），等待动画播完重新生成
        int[] entityIds = glowEntities.remove(loc);
        if (entityIds != null) {
            DisplayEntityUtil.destroyEntity(entityIds);
        }
        // DOOR_SYNC_DELAY_TICKS 后重生：玩家仍持有匹配钥匙（仍在匹配集合）且未打开/未生成时，
        // 按真实方块最新状态重新生成（spawnGlowDisplayDoor 读取 open/hinge，无需刷新已有实体）
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingDoorSync.remove(loc);
            Set<UUID> holders = glowMatched.get(loc);
            if (holders != null && !openChests.contains(loc) && !glowEntities.containsKey(loc)) {
                spawnGlowEntities(loc, holders, config.getKeyGlowColor());
            }
        }, DOOR_SYNC_DELAY_TICKS);
        BukkitTask old = pendingDoorSync.put(loc, task);
        if (old != null) {
            old.cancel();
        }
    }

    /** 箱子打开时调用：取消未完成的延迟恢复，若该位置正在发光则移除发光实体并标记打开中。 */
    public void onChestOpen(BlockLocation loc) {
        // 双箱任一侧打开均按统一 key 处理
        BlockLocation key = sideToGlowKey.getOrDefault(loc, loc);
        BukkitTask task = pendingRestore.remove(key);
        if (task != null) {
            task.cancel();
        }
        int[] entityIds = glowEntities.remove(key);
        if (entityIds != null) {
            DisplayEntityUtil.destroyEntity(entityIds);
            doorGlowOpen.remove(key);
            openChests.add(key);
        }
        // 发光实体已移除：取消该位置的延迟刷新任务，避免任务执行时对已销毁实体发包
        BukkitTask doorTask = pendingDoorSync.remove(key);
        if (doorTask != null) {
            doorTask.cancel();
        }
    }

    /** 箱子关闭时调用：延迟 0.5 秒恢复发光（若玩家仍持有匹配钥匙，下个对账周期自动重新生成）。 */
    public void onChestClose(BlockLocation loc) {
        // 双箱任一侧关闭均按统一 key 处理
        BlockLocation key = sideToGlowKey.getOrDefault(loc, loc);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingRestore.remove(key);
            openChests.remove(key);
        }, RESTORE_DELAY_TICKS);
        pendingRestore.put(key, task);
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
        List<Integer> ids = new ArrayList<>();
        for (int[] entityIds : glowEntities.values()) {
            for (int entityId : entityIds) {
                ids.add(entityId);
            }
        }
        glowEntities.clear();
        doorGlowOpen.clear();
        pendingDoorSync.values().forEach(BukkitTask::cancel);
        pendingDoorSync.clear();
        DisplayEntityUtil.destroyEntity(ids.stream().mapToInt(Integer::intValue).toArray());
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

    /** 返回方块及其"另一半"（双箱左右 / 门上下半，普通方块返回自身），供粒子与发光覆盖整个结构。 */
    private List<Block> chestSides(Block block) {
        List<Block> sides = new ArrayList<>();
        sides.add(block);
        if (block.getState() instanceof Chest chestState) {
            InventoryHolder holder = chestState.getInventory().getHolder();
            if (holder instanceof DoubleChest doubleChest) {
                for (InventoryHolder side : new InventoryHolder[]{doubleChest.getLeftSide(), doubleChest.getRightSide()}) {
                    if (side instanceof Chest sideChest) {
                        Block sideBlock = sideChest.getBlock();
                        if (sideBlock != null && !sideBlock.equals(block)) {
                            sides.add(sideBlock);
                        }
                    }
                }
            }
        } else {
            // 门：上下半一体，发光/粒子需覆盖整扇门
            Block other = doorCounterpart(block);
            if (other != null) {
                sides.add(other);
            }
        }
        return sides;
    }

    /** 门的另一半（上下半）；非门方块或另一半缺失时返回 null。 */
    private Block doorCounterpart(Block block) {
        if (!isDoorMaterial(block.getType())) {
            return null;
        }
        Block up = block.getRelative(org.bukkit.block.BlockFace.UP);
        Block down = block.getRelative(org.bukkit.block.BlockFace.DOWN);
        if (up.getType() == block.getType()) {
            return up;
        }
        if (down.getType() == block.getType()) {
            return down;
        }
        return null;
    }

    /** 是否为门方块（木门/铁门等上下半一体的 *_DOOR；陷阱门 *_TRAPDOOR 不在此列）。 */
    private boolean isDoorMaterial(Material material) {
        return material != null && material.name().endsWith("_DOOR") && !material.name().endsWith("_TRAPDOOR");
    }
}
