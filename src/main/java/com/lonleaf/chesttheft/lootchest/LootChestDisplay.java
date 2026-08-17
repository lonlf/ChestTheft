package com.lonleaf.chesttheft.lootchest;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.StaticSound;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.meta.display.BlockDisplayMeta;
import me.tofaa.entitylib.meta.other.InteractionMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;
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

import java.util.Locale;

/**
 * 战利品箱展示工具：
 * display 模式基于 EntityLib 生成/移除纯客户端展示实体（BlockDisplay）与交互载体（Interaction），
 * block 模式放置/移除真实箱子方块；另含 PacketEvents 发包特效（开箱发光/粒子/音效）。
 */
public final class LootChestDisplay {

    private LootChestDisplay() {
    }

    // ==================== display 模式：EntityLib 纯客户端实体 ====================

    /**
     * 生成展示实体：纯客户端 BlockDisplay（EntityLib 发包创建，服务端不建实体），
     * 显示指定方块材质，返回实体 ID（调用方登记用于交互反查与移除）。
     */
    public static int spawnDisplay(Location location, Material material) {
        WrapperEntity entity = new WrapperEntity(EntityTypes.BLOCK_DISPLAY);
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            // 偏移 -0.5 使模型正好填满所在方块（其余用协议默认值）
            meta.setTranslation(new Vector3f(-0.5f, 0f, -0.5f));
            meta.setBlockState(blockState(material));
        });
        spawnToWorld(entity, location);
        return entity.getEntityId();
    }

    /**
     * 生成交互载体（Interaction 实体）：BlockDisplay 的 hitbox 服务端固定且无法修改，客户端难以点击选中；
     * 叠加一个纯客户端 Interaction 实体提供可点击 hitbox，宽高 1.0×1.0 覆盖整个方块、左右键均可交互。
     * 玩家点击它发送 INTERACT_ENTITY 包（由收包监听反查箱子）。
     */
    public static int spawnInteraction(Location location) {
        WrapperEntity entity = new WrapperEntity(EntityTypes.INTERACTION);
        entity.consumeEntityMeta(InteractionMeta.class, meta -> {
            meta.setWidth(1.0f);
            meta.setHeight(1.0f);
            meta.setResponsive(true);
        });
        spawnToWorld(entity, location);
        return entity.getEntityId();
    }

    /**
     * 生成发光展示实体：手持钥匙时叠加在对应锁方块上的 BlockDisplay。
     * 本体与锁方块同材质且复制其朝向（视觉无缝，避免朝向错位出现"两个箱子"），
     * scale 微扩 1.001 防与真实方块 z-fighting，开启发光位并以 ARGB 指定 glow color override。
     * 返回实体 ID，调用方负责在不再需要时移除。
     */
    public static int spawnGlowDisplay(Block block, int argb) {
        WrapperEntity entity = new WrapperEntity(EntityTypes.BLOCK_DISPLAY);
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setTranslation(new Vector3f(-0.5f, 0f, -0.5f));
            meta.setScale(new Vector3f(1.001f, 1.001f, 1.001f));
            meta.setBlockState(blockStateOf(block));
            meta.setGlowing(true);
            meta.setGlowColorOverride(argb);
        });
        spawnToWorld(entity, block.getLocation());
        return entity.getEntityId();
    }

    /**
     * 向单个玩家重发展示实体与交互载体（玩家进服/换世界时调用）。
     * EntityLib addViewer 会对该玩家补发 spawn + metadata 包，实体 ID 保持不变。
     */
    public static void respawnTo(int displayEntityId, int interactEntityId, Player viewer) {
        WrapperEntity display = getEntity(displayEntityId);
        if (display != null) {
            display.addViewer(viewer.getUniqueId());
        }
        WrapperEntity interact = getEntity(interactEntityId);
        if (interact != null) {
            interact.addViewer(viewer.getUniqueId());
        }
    }

    /** 移除纯客户端实体（EntityLib 销毁并向全部可见玩家广播 DestroyEntities 包）。 */
    public static void destroyEntity(int... entityIds) {
        if (entityIds == null || entityIds.length == 0) {
            return;
        }
        for (int entityId : entityIds) {
            WrapperEntity entity = getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    /** 从 EntityLib 默认容器按实体 ID 取实体，不存在时返回 null。 */
    private static WrapperEntity getEntity(int entityId) {
        return EntityLib.getApi().getEntity(entityId);
    }

    /** 生成在方块中心底部，并让该世界全部玩家可见（纯客户端实体需逐玩家发包）。 */
    private static void spawnToWorld(WrapperEntity entity, Location location) {
        World world = location.getWorld();
        if (world != null) {
            for (Player viewer : world.getPlayers()) {
                entity.addViewer(viewer.getUniqueId());
            }
        }
        entity.spawn(new com.github.retrooper.packetevents.protocol.world.Location(
                location.getX() + 0.5, location.getY(), location.getZ() + 0.5, 0f, 0f));
    }

    /** Bukkit 材质 → 全局方块状态，非法材质回退箱子。 */
    private static WrappedBlockState blockState(Material material) {
        // 注意：不能用 getByString——它只匹配"无属性后缀"的键（如 air），对有属性的方块（chest 等）
        // 永远返回 AIR（globalId=0），导致 BlockDisplay 渲染成空气。这里按 StateType 取默认状态。
        StateType type = StateTypes.getByName(material.name().toLowerCase(Locale.ROOT));
        if (type == null) {
            type = StateTypes.CHEST;
        }
        WrappedBlockState state = WrappedBlockState.getDefaultState(type);
        if (state == null || state.getGlobalId() == 0) {
            state = WrappedBlockState.getDefaultState(StateTypes.CHEST);
        }
        if (state == null || state.getGlobalId() == 0) {
            Bukkit.getLogger().warning("[ChestTheft] 无法获取方块状态 globalId（BlockDisplay 将不可见）: material="
                    + material + " type=" + type);
        }
        return state;
    }

    /** 按真实方块生成全局方块状态：材质默认状态 + 复制朝向（箱子/陷阱箱/木桶等含朝向的方块）。 */
    private static WrappedBlockState blockStateOf(Block block) {
        WrappedBlockState state = blockState(block.getType());
        if (state == null) {
            return state;
        }
        org.bukkit.block.data.BlockData data = block.getState().getBlockData();
        if (data instanceof org.bukkit.block.data.Directional directional) {
            state.setFacing(mapFacing(directional.getFacing()));
        }
        return state;
    }

    /** Bukkit 方块朝向 → PacketEvents BlockFace（保证展示实体与真实方块朝向一致）。 */
    private static com.github.retrooper.packetevents.protocol.world.BlockFace mapFacing(
            org.bukkit.block.BlockFace face) {
        return switch (face) {
            case NORTH -> com.github.retrooper.packetevents.protocol.world.BlockFace.NORTH;
            case EAST -> com.github.retrooper.packetevents.protocol.world.BlockFace.EAST;
            case SOUTH -> com.github.retrooper.packetevents.protocol.world.BlockFace.SOUTH;
            case WEST -> com.github.retrooper.packetevents.protocol.world.BlockFace.WEST;
            case UP -> com.github.retrooper.packetevents.protocol.world.BlockFace.UP;
            case DOWN -> com.github.retrooper.packetevents.protocol.world.BlockFace.DOWN;
            default -> com.github.retrooper.packetevents.protocol.world.BlockFace.NORTH;
        };
    }

    // ==================== block 模式：真实容器方块 ====================

    /** 是否为可放置真实容器方块的材质（block 模式仅支持这些）。 */
    public static boolean isContainerMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL;
    }

    /**
     * 在指定位置放置真实容器方块并标记 PDC；位置被占用时尝试上方一格，
     * 均不可放置或非容器材质时返回 null（调用方放弃生成）。
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
     * 按档案开箱特效配置播放：发光（EntityLib meta：发光位 + glow color override）、
     * 扬起尘土粒子（SpawnParticle 包）与开箱音效（NamedSoundEffect 包）。
     * 发光/粒子未配置则跳过对应效果；音效缺省 BLOCK_CHEST_OPEN，音量/音调按配置。
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
                Bukkit.getLogger().warning("战利品箱开箱粒子类型 '" + particles.getType().name()
                        + "' 暂不支持 PacketEvents 发包，已忽略（可用 CLOUD/FLAME/SMOKE/END_ROD 等）");
            }
        }
        // 发光：EntityLib meta（发光位 + glow color override），改动自动同步给全部可见玩家，
        // 定时恢复默认（关闭发光、glow color -1）
        LootChestProfile.OpenEffectionConfig.GlowConfig glow = eff.getGlow();
        if (glow != null && chest.getDisplayEntityId() != -1) {
            setDisplayGlow(chest.getDisplayEntityId(), true, glow.getColor().asARGB());
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    setDisplayGlow(chest.getDisplayEntityId(), false, -1), glow.getDurationTicks());
        }
    }

    /** 对展示实体设置发光状态与发光颜色（EntityLib meta 自动同步给可见玩家）。 */
    private static void setDisplayGlow(int displayEntityId, boolean glowing, int argb) {
        WrapperEntity entity = getEntity(displayEntityId);
        if (entity == null) {
            return;
        }
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setGlowing(glowing);
            meta.setGlowColorOverride(argb);
        });
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

    /** 向指定位置附近的玩家发送 PacketEvents 包。 */
    private static void sendToNearby(Location loc, double radius, PacketWrapper<?>... wrappers) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        double radiusSq = radius * radius;
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(loc) <= radiusSq) {
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
