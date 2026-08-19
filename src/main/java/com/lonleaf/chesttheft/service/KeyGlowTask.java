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
        Map<BlockLocation, Set<UUID>> glowMatched = new HashMap<>();
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
                LootChestDisplay.destroyEntity(entry.getValue());
                iterator.remove();
            } else {
                LootChestDisplay.syncGlowViewers(entry.getValue(), holders);
            }
        }
        for (BlockLocation loc : glowMatched.keySet()) {
            if (openChests.contains(loc) || glowEntities.containsKey(loc)) {
                continue;
            }
            World world = loc.toWorld();
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(loc.getX(), loc.getY(), loc.getZ());
            List<Block> sides = chestSides(block);
            int[] entityIds;
            if (sides.size() > 1) {
                // 双箱：左右两个真实方块状态（type=left/right）的展示实体并排，还原双箱外观
                entityIds = LootChestDisplay.spawnGlowDisplayDouble(block, sides.get(1), glowArgb(glowColor), glowMatched.get(loc));
            } else {
                entityIds = new int[]{LootChestDisplay.spawnGlowDisplay(block, glowArgb(glowColor), glowMatched.get(loc))};
            }
            glowEntities.put(loc, entityIds);
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
            LootChestDisplay.destroyEntity(entityIds);
            openChests.add(key);
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
        LootChestDisplay.destroyEntity(ids.stream().mapToInt(Integer::intValue).toArray());
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

    /** 返回方块及双箱对侧（双箱返回两侧、普通箱子返回自身），供粒子与发光覆盖整个容器。 */
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
        }
        return sides;
    }
}
