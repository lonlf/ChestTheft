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
    /** 门发光实体生成时对应的门 open 状态（仅门位置登记）：低频兜底轮询据此检测红石/活塞驱动的门开关
     *  （玩家交互由 onDoorInteract 事件驱动即时处理，此处仅覆盖无交互事件的状态变化）。 */
    private final Map<BlockLocation, Boolean> doorGlowOpen = new HashMap<>();
    /** 兜底轮询计数：每 DOOR_POLL_INTERVAL 次对账（对账每 5 tick 一次）执行一次门状态检查。 */
    private int doorPollCounter;
    /** 门状态兜底轮询间隔（对账次数；4 次 = 20 tick = 1 秒）。 */
    private static final int DOOR_POLL_INTERVAL = 4;
    /** 门交互后延迟重生的调度任务（按位置登记）：销毁旧实体等转动动画播完再生成新状态实体，避免静态实体与动画错位。 */
    private final Map<BlockLocation, BukkitTask> pendingDoorSync = new HashMap<>();
    /** 门交互后延迟重生的时长（5 tick），等待客户端门转动动画播完。 */
    private static final long DOOR_SYNC_DELAY_TICKS = 5L;
    /** 打开中的箱子位置 → 打开时刻（ms）：打开时移除发光实体（避免原版开盖动画与静态展示实体错位），
     *  关闭后延迟恢复；超时清理防残留（箱子被炸毁/玩家异常下线未触发关闭事件时，标记残留会永久抑制发光）。 */
    private final Map<BlockLocation, Long> openChests = new HashMap<>();
    /** "打开中"标记的最大存活时长（ms）：超过视为残留，允许重新生成发光。 */
    private static final long OPEN_CHEST_STALE_MS = 60_000L;
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
        // 第一阶段：收集手持配对钥匙的玩家（配对位置 → 玩家列表）。
        // 每个位置每轮只查询一次锁凭证，避免多玩家配对同一把锁时的重复 DB 查询
        Map<BlockLocation, List<Player>> holders = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!itemManager.isType(hand, ItemType.KEY)) {
                continue;
            }
            BlockLocation paired = itemManager.getPairedLock(hand);
            if (paired != null) {
                holders.computeIfAbsent(paired, k -> new ArrayList<>()).add(player);
            }
        }
        for (Map.Entry<BlockLocation, List<Player>> entry : holders.entrySet()) {
            BlockLocation paired = entry.getKey();
            World world = paired.toWorld();
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(paired.getX(), paired.getY(), paired.getZ());
            if (block.getType().isAir()) {
                continue;
            }
            // 每位置只查一次锁凭证（原实现对每个持钥匙玩家各查一次）；配对值相等且锁仍存在才继续
            String token = chestService.getLockToken(block);
            if (token == null) {
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
            for (Player player : entry.getValue()) {
                // 玩家可能已换世界：distanceSquared 跨世界会抛异常并中断整个扫描周期
                if (!world.equals(player.getWorld())) {
                    continue;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!token.equals(itemManager.getPairedToken(hand))) {
                    continue;
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
        }
        // 对账发光展示实体：仍匹配的位置同步观众集合，不再匹配的位置移除
        Iterator<Map.Entry<BlockLocation, int[]>> iterator = glowEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockLocation, int[]> entry = iterator.next();
            Set<UUID> matchedHolders = glowMatched.get(entry.getKey());
            if (matchedHolders == null) {
                DisplayEntityUtil.destroyEntity(entry.getValue());
                iterator.remove();
            } else {
                DisplayEntityUtil.syncGlowViewers(entry.getValue(), matchedHolders);
            }
        }
        // 清理长期"打开中"残留（箱子被炸毁/玩家异常下线未触发关闭事件）：超时后允许重新生成发光
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockLocation, Long>> openIt = openChests.entrySet().iterator();
        while (openIt.hasNext()) {
            if (now - openIt.next().getValue() > OPEN_CHEST_STALE_MS) {
                openIt.remove();
            }
        }
        for (BlockLocation loc : glowMatched.keySet()) {
            // 打开中 / 已存在实体 / 门转动等待重生（销毁后 5 tick 内）的位置跳过，不重复生成
            if (openChests.containsKey(loc) || glowEntities.containsKey(loc) || pendingDoorSync.containsKey(loc)) {
                continue;
            }
            spawnGlowEntities(loc, glowMatched.get(loc), glowColor);
        }
        // 低频兜底轮询门状态（红石/活塞等非交互驱动）：每 DOOR_POLL_INTERVAL 次对账检查一次
        if (++doorPollCounter >= DOOR_POLL_INTERVAL) {
            doorPollCounter = 0;
            pollDoorGlowState();
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
                // 记录实体生成时对应的门 open 状态，供低频兜底轮询对比（红石/活塞驱动时无交互事件）
                if (block.getState().getBlockData() instanceof org.bukkit.block.data.type.Door doorData) {
                    doorGlowOpen.put(loc, doorData.isOpen());
                }
            } else {
                // 双箱：左右两个真实方块状态（type=left/right）的展示实体并排，还原双箱外观
                entityIds = DisplayEntityUtil.spawnGlowDisplayDouble(block, sides.get(1), glowArgb(glowColor), holders);
            }
        } else {
            entityIds = new int[]{DisplayEntityUtil.spawnGlowDisplay(block, glowArgb(glowColor), holders)};
        }
        glowEntities.put(loc, entityIds);
    }

    /** 销毁门的发光展示实体并安排 5 tick 后按最新状态重生（事件驱动与兜底轮询共用）：
     *  取消待进行的重生任务；无实体/无匹配玩家时重生任务空转一次后结束。 */
    private void destroyAndRespawnDoor(BlockLocation key) {
        BukkitTask oldTask = pendingDoorSync.remove(key);
        if (oldTask != null) {
            oldTask.cancel();
        }
        int[] entityIds = glowEntities.remove(key);
        if (entityIds != null) {
            DisplayEntityUtil.destroyEntity(entityIds);
            doorGlowOpen.remove(key);
        }
        // 5 tick 后重生：玩家仍持有匹配钥匙（仍在匹配集合）且未打开/未生成时按最新状态重新生成
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingDoorSync.remove(key);
            Set<UUID> holders = glowMatched.get(key);
            if (holders != null && !openChests.containsKey(key) && !glowEntities.containsKey(key)) {
                spawnGlowEntities(key, holders, config.getKeyGlowColor());
            }
        }, DOOR_SYNC_DELAY_TICKS);
        pendingDoorSync.put(key, task);
    }

    /** 玩家交互门（开/关）时调用：立即销毁该门的发光展示实体，5 tick 后按最新门状态重新生成
     *  （门转动动画约 5 tick，重生时 spawnGlowDisplayDoor 读取真实方块最新 open/hinge 状态）。
     *  事件驱动替代原先每 5 tick 轮询门状态的逻辑，响应更快。 */
    public void onDoorInteract(BlockLocation loc) {
        BlockLocation key = sideToGlowKey.getOrDefault(loc, loc);
        destroyAndRespawnDoor(key);
    }

    /** 低频兜底轮询：检测红石/活塞等非玩家交互驱动的门状态变化（事件驱动覆盖不到），变化则销毁实体并安排重生。
     *  仅遍历当前正在发光的门（数量极少，开销可忽略）；玩家交互场景已由事件驱动即时处理，此处不重复。 */
    private void pollDoorGlowState() {
        for (BlockLocation key : doorGlowOpen.keySet().toArray(new BlockLocation[0])) {
            World world = key.toWorld();
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(key.getX(), key.getY(), key.getZ());
            if (!isDoorMaterial(block.getType())
                    || !(block.getState().getBlockData() instanceof org.bukkit.block.data.type.Door doorData)) {
                continue;
            }
            if (doorGlowOpen.get(key) != null && doorGlowOpen.get(key) != doorData.isOpen()) {
                destroyAndRespawnDoor(key);
            }
        }
    }

    /** 箱子打开时调用：取消未完成的延迟恢复，若该位置正在发光则移除发光实体，并标记打开中
     *  （打开期间 spawn 对账跳过该位置，避免发光实体覆盖在已打开的箱子上；无条件登记，消除
     *  "打开瞬间无发光实体"导致后续仍会生成的竞态）。 */
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
        }
        // 发光实体已移除：取消该位置的延迟刷新任务，避免任务执行时对已销毁实体发包
        BukkitTask doorTask = pendingDoorSync.remove(key);
        if (doorTask != null) {
            doorTask.cancel();
        }
        // 无条件登记打开中（无论打开前是否有发光）：关闭后由 onChestClose 延迟恢复，超时由对账清理
        openChests.put(key, System.currentTimeMillis());
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
