package com.lonleaf.chesttheft.lootchest;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.display.DisplayEntityUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.StaticSound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 战利品箱展示工具：block 模式真实容器方块与发包特效（粒子/音效/发光）。
 * 纯客户端展示实体（BlockDisplay/Interaction）的统一管理见 {@link DisplayEntityUtil}。
 */
public final class LootChestDisplay {

    private LootChestDisplay() {
    }

    // ==================== block 模式：真实容器方块 ====================

    /** 是否为可放置真实容器方块的材质（block 模式仅支持这些）。 */
    public static boolean isContainerMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL;
    }

    /**
     * 放置真实容器方块并标记 PDC；位置被占用时尝试上方一格，均不可放置或非容器材质时返回 null。
     */
    public static Block placeChestBlock(Location location, Material material, NamespacedKey blockKey) {
        Block block = location.getBlock();
        if (!block.getType().isAir()) {
            Block above = location.clone().add(0, 1, 0).getBlock();
            if (!above.getType().isAir()) {
                return null;
            }
            block = above;
        }
        block.setType(material, false);
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            block.setType(Material.AIR, false);
            return null;
        }
        container.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, "1");
        container.update();
        return block;
    }

    /** 拆除真实箱子方块且不产生掉落（block 模式；物品已由调用方处理）。 */
    public static void removeChestBlock(Block block) {
        if (block == null) {
            return;
        }
        BlockState state = block.getState();
        if (state instanceof Container container) {
            container.getInventory().clear();
        }
        block.setType(Material.AIR, false);
    }

    // ==================== 发包特效 ====================

    /**
     * 按档案开箱特效配置播放发光、扬起尘土粒子与开箱音效（PacketEvents 发包）；
     * 未配置的项跳过，音效缺省 BLOCK_CHEST_OPEN。
     */
    public static void playOpenEffects(Plugin plugin, LootChest chest, Player player) {
        LootChestProfile profile = chest.getProfile();
        LootChestProfile.OpenEffectionConfig eff = profile != null ? profile.getOpenEffection() : null;
        if (eff == null) {
            return;
        }
        Location loc = chest.getLocation();
        LootChestProfile.OpenEffectionConfig.SoundConfig sound = eff.getSound();
        if (sound != null) {
            NamespacedKey key = sound.getType().getKey();
            WrapperPlayServerSoundEffect wrapper = new WrapperPlayServerSoundEffect(
                    new StaticSound(new ResourceLocation(key.getNamespace(), key.getKey()), null),
                    SoundCategory.BLOCK,
                    new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                    sound.getVolume(), sound.getPitch());
            sendToNearby(loc, 16.0, wrapper);
        }
        LootChestProfile.OpenEffectionConfig.ParticlesConfig particles = eff.getParticles();
        if (particles != null) {
            com.github.retrooper.packetevents.protocol.particle.Particle<?> particle = mapParticle(particles.getType().name());
            if (particle != null) {
                // 盖子位置的扬起尘土（SpawnParticle 包）
                Location center = loc.clone().add(0.5, 0.9, 0.5);
                WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(
                        particle, false,
                        new Vector3d(center.getX(), center.getY(), center.getZ()),
                        new Vector3f(0.4f, 0.4f, 0.4f),
                        0.05f, particles.getCount());
                sendToNearby(loc, 64.0, wrapper);
            } else {
                Bukkit.getLogger().warning(Messages.getLog(Messages.LOG_LOOT_OPEN_PARTICLE_INVALID, particles.getType().name()));
            }
        }
        // 发光：EntityLib meta（发光位 + glow color override），改动自动同步给全部可见玩家，
        // 定时恢复默认（关闭发光、glow color -1）
        LootChestProfile.OpenEffectionConfig.GlowConfig glow = eff.getGlow();
        if (glow != null && chest.getDisplayEntityId() != -1) {
            DisplayEntityUtil.setDisplayGlow(chest.getDisplayEntityId(), true, glow.getColor().asARGB());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    DisplayEntityUtil.setDisplayGlow(chest.getDisplayEntityId(), false, -1), glow.getDurationTicks());
        }
    }

    /** 移除战利品箱时的烟雾粒子特效（SpawnParticle 包）。 */
    public static void playRemoveParticles(Location location) {
        Location center = location.clone().add(0.5, 0.5, 0.5);
        WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(
                particle(ParticleTypes.CLOUD), false,
                new Vector3d(center.getX(), center.getY(), center.getZ()),
                new Vector3f(0.5f, 0.5f, 0.5f),
                0.05f, 20);
        sendToNearby(location, 64.0, wrapper);
    }

    // ==================== 发包工具 ====================

    /** 向指定位置附近的玩家发送 PacketEvents 包（chunk 级粗筛 + 精确距离，纯 Spigot 兼容）。 */
    private static void sendToNearby(Location loc, double radius, PacketWrapper<?>... wrappers) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        double radiusSq = radius * radius;
        // chunk 级粗筛：远离的玩家用整数比较跳过，避免高在线大世界下对全体玩家做浮点距离计算
        int chunkRadius = Math.max(1, (int) Math.ceil(radius / 16.0));
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        for (Player viewer : world.getPlayers()) {
            Location v = viewer.getLocation();
            if (Math.abs((v.getBlockX() >> 4) - cx) > chunkRadius
                    || Math.abs((v.getBlockZ() >> 4) - cz) > chunkRadius) {
                continue;
            }
            if (v.distanceSquared(loc) <= radiusSq) {
                for (PacketWrapper<?> wrapper : wrappers) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
                }
            }
        }
    }

    /** Bukkit 粒子名 → PacketEvents 粒子（兼容 1.19.4 旧名与 1.20+ 新名；DUST 默认白色）。 */
    private static com.github.retrooper.packetevents.protocol.particle.Particle<?> mapParticle(String name) {
        switch (name) {
            case "CLOUD":
                return particle(ParticleTypes.CLOUD);
            case "FLAME":
                return particle(ParticleTypes.FLAME);
            case "SMOKE":
            case "SMOKE_NORMAL":
                return particle(ParticleTypes.SMOKE);
            case "LARGE_SMOKE":
            case "SMOKE_LARGE":
                return particle(ParticleTypes.LARGE_SMOKE);
            case "END_ROD":
                return particle(ParticleTypes.END_ROD);
            case "CRIT":
                return particle(ParticleTypes.CRIT);
            case "ENCHANT":
                return particle(ParticleTypes.ENCHANT);
            case "VILLAGER_HAPPY":
                return particle(ParticleTypes.HAPPY_VILLAGER);
            case "VILLAGER_ANGRY":
            case "ANGRY_VILLAGER":
                return particle(ParticleTypes.ANGRY_VILLAGER);
            case "NOTE":
                return particle(ParticleTypes.NOTE);
            case "PORTAL":
                return particle(ParticleTypes.PORTAL);
            case "LAVA":
                return particle(ParticleTypes.LAVA);
            case "SOUL":
                return particle(ParticleTypes.SOUL);
            case "ASH":
                return particle(ParticleTypes.ASH);
            case "BUBBLE":
                return particle(ParticleTypes.BUBBLE);
            case "SPLASH":
                return particle(ParticleTypes.SPLASH);
            case "POOF":
            case "EXPLOSION_NORMAL":
            case "EXPLOSION":
            case "EXPLOSION_LARGE":
                return particle(ParticleTypes.EXPLOSION);
            case "EXPLOSION_HUGE":
            case "EXPLOSION_EMITTER":
                return particle(ParticleTypes.EXPLOSION_EMITTER);
            case "FIREWORK":
                return particle(ParticleTypes.FIREWORK);
            case "COMPOSTER":
                return particle(ParticleTypes.COMPOSTER);
            case "DUST":
            case "REDSTONE":
                return new com.github.retrooper.packetevents.protocol.particle.Particle<>(
                        ParticleTypes.DUST,
                        new ParticleDustData(1.0f, new com.github.retrooper.packetevents.protocol.color.Color(255, 255, 255)));
            default:
                return null;
        }
    }

    private static com.github.retrooper.packetevents.protocol.particle.Particle<?> particle(
            com.github.retrooper.packetevents.protocol.particle.type.ParticleType<?> type) {
        // PacketEvents 2.13 要求 data 非 null（内部直接调用 isEmpty()），无数据粒子使用空数据
        return new com.github.retrooper.packetevents.protocol.particle.Particle<>(type, ParticleData.emptyData());
    }
}
