package com.lonleaf.chesttheft.display;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.lonleaf.chesttheft.config.Messages;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.meta.display.BlockDisplayMeta;
import me.tofaa.entitylib.meta.other.InteractionMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端展示实体工具：EntityLib 纯客户端实体（BlockDisplay/Interaction）的统一生成、观众同步、
 * 方块状态构建、发光控制与销毁。供钥匙发光（KeyGlowTask）、战利品箱（LootChest）等跨业务复用，
 * 与业务方块/特效逻辑分离。
 */
public final class DisplayEntityUtil {

    private DisplayEntityUtil() {
    }

    // ==================== 生成 ====================

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
     * 生成交互载体：BlockDisplay 的 hitbox 服务端固定不可改，叠加纯客户端 Interaction 提供可点击 hitbox
     * （宽高 1.0×1.0），玩家点击发送 INTERACT_ENTITY 包由收包监听反查箱子。
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
     * 生成发光展示实体：与锁方块同材质并复制朝向，scale 微扩 1.001 防 z-fighting，
     * 仅对指定观众（钥匙持有者）可见；返回实体 ID。
     */
    public static int spawnGlowDisplay(Block block, int argb, Collection<UUID> viewers) {
        WrapperEntity entity = new WrapperEntity(EntityTypes.BLOCK_DISPLAY);
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setTranslation(new Vector3f(-0.5f, 0f, -0.5f));
            meta.setScale(new Vector3f(1.001f, 1.001f, 1.001f));
            meta.setBlockState(blockStateOf(block));
            meta.setGlowing(true);
            meta.setGlowColorOverride(argb);
        });
        spawnTo(entity, block.getLocation(), viewers);
        return entity.getEntityId();
    }

    /**
     * 生成覆盖双箱的发光展示实体：左右两个真实方块状态（type=left/type=right）的 BlockDisplay
     * 分别放置于两个箱子块上，无缩放拉伸、还原真实双箱外观；返回两个实体 ID（先左后右）。
     */
    public static int[] spawnGlowDisplayDouble(Block block, Block other, int argb, Collection<UUID> viewers) {
        int leftId = spawnGlowDisplayHalf(block,
                com.github.retrooper.packetevents.protocol.world.states.enums.Type.LEFT, argb, viewers);
        int rightId = spawnGlowDisplayHalf(other,
                com.github.retrooper.packetevents.protocol.world.states.enums.Type.RIGHT, argb, viewers);
        return new int[]{leftId, rightId};
    }

    /**
     * 生成覆盖整扇门的发光展示实体：上下两个真实半块状态（half=upper/lower）的 BlockDisplay
     * 分别放置于两个方块上，还原真实门外观；返回两个实体 ID。
     */
    public static int[] spawnGlowDisplayDoor(Block block, Block other, int argb, Collection<UUID> viewers) {
        int firstId = spawnGlowDisplayHalf(block, argb, viewers);
        int secondId = spawnGlowDisplayHalf(other, argb, viewers);
        return new int[]{firstId, secondId};
    }

    /** 生成单块（含双箱半块）的发光展示实体：scale 微扩 1.001 防 z-fighting；左右类型以真实方块数据为准。 */
    private static int spawnGlowDisplayHalf(Block block,
                                            com.github.retrooper.packetevents.protocol.world.states.enums.Type fallbackChestType,
                                            int argb, Collection<UUID> viewers) {
        WrapperEntity entity = new WrapperEntity(EntityTypes.BLOCK_DISPLAY);
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setTranslation(new Vector3f(-0.5f, 0f, -0.5f));
            meta.setScale(new Vector3f(1.001f, 1.001f, 1.001f));
            meta.setBlockState(blockStateOf(block, fallbackChestType));
            meta.setGlowing(true);
            meta.setGlowColorOverride(argb);
        });
        spawnTo(entity, block.getLocation(), viewers);
        return entity.getEntityId();
    }

    /** 生成单块（含门半块）的发光展示实体：scale 微扩 1.001 防 z-fighting；门半块类型以真实方块数据为准。 */
    private static int spawnGlowDisplayHalf(Block block, int argb, Collection<UUID> viewers) {
        WrapperEntity entity = new WrapperEntity(EntityTypes.BLOCK_DISPLAY);
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setTranslation(new Vector3f(-0.5f, 0f, -0.5f));
            meta.setScale(new Vector3f(1.001f, 1.001f, 1.001f));
            meta.setBlockState(blockStateOf(block));
            meta.setGlowing(true);
            meta.setGlowColorOverride(argb);
        });
        spawnTo(entity, block.getLocation(), viewers);
        return entity.getEntityId();
    }

    // ==================== 观众与状态同步 ====================

    /**
     * 同步发光展示实体的可见玩家集合（仅钥匙持有者可见）：
     * 新增观众补发 spawn + metadata，不再可见的观众广播销毁；离线的旧观众无需处理（客户端已断开）。
     */
    public static void syncGlowViewers(int entityId, Collection<UUID> holders) {
        WrapperEntity entity = getEntity(entityId);
        if (entity == null) {
            return;
        }
        Set<UUID> current = entity.getViewers();
        Set<UUID> target = holders == null ? Collections.emptySet() : new HashSet<>(holders);
        for (UUID viewer : target) {
            if (!current.contains(viewer)) {
                entity.addViewer(viewer);
            }
        }
        for (UUID viewer : current) {
            if (!target.contains(viewer) && Bukkit.getPlayer(viewer) != null) {
                entity.removeViewer(viewer);
            }
        }
    }

    /** 同步多个发光展示实体的可见玩家集合（双箱左右两个实体同步同一批观众）。 */
    public static void syncGlowViewers(int[] entityIds, Collection<UUID> holders) {
        for (int entityId : entityIds) {
            syncGlowViewers(entityId, holders);
        }
    }

    /** 对展示实体设置发光状态与发光颜色（EntityLib meta 自动同步给可见玩家）。 */
    public static void setDisplayGlow(int displayEntityId, boolean glowing, int argb) {
        WrapperEntity entity = getEntity(displayEntityId);
        if (entity == null) {
            return;
        }
        entity.consumeEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setGlowing(glowing);
            meta.setGlowColorOverride(argb);
        });
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

    // ==================== 实体与方块状态构建 ====================

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

    /** 生成在方块中心底部，仅让指定玩家可见（纯客户端实体需逐玩家发包）。 */
    private static void spawnTo(WrapperEntity entity, Location location, Collection<UUID> viewers) {
        if (viewers != null) {
            for (UUID viewer : viewers) {
                entity.addViewer(viewer);
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
            Bukkit.getLogger().warning(Messages.getLog(Messages.LOG_LOOT_DISPLAY_GLOBAL_ID_FAIL, material, type));
        }
        return state;
    }

    /** 按真实方块生成全局方块状态：材质默认状态 + 复制朝向（箱子/陷阱箱/木桶等含朝向的方块）。 */
    private static WrappedBlockState blockStateOf(Block block) {
        return blockStateOf(block, null);
    }

    /**
     * 按真实方块生成全局方块状态：材质默认状态 + 复制朝向，双箱两侧额外复制左右类型
     * （type=left/right，展示实体还原真实双箱外观）；fallbackChestType 为单箱数据时的兜底类型。
     */
    private static WrappedBlockState blockStateOf(Block block,
                                                  com.github.retrooper.packetevents.protocol.world.states.enums.Type fallbackChestType) {
        WrappedBlockState state = blockState(block.getType());
        if (state == null) {
            return state;
        }
        org.bukkit.block.data.BlockData data = block.getState().getBlockData();
        if (data instanceof org.bukkit.block.data.Directional directional) {
            state.setFacing(mapFacing(directional.getFacing()));
        }
        // 门：复制上下半属性（half=upper/lower）、开关状态（open）与合页方向（hinge），
        // 两个半块展示实体还原整扇门外观，门转动后由 syncGlowDoorState 刷新跟随
        if (data instanceof org.bukkit.block.data.type.Door doorData) {
            state.setHalf(doorData.getHalf() == org.bukkit.block.data.Bisected.Half.TOP
                    ? com.github.retrooper.packetevents.protocol.world.states.enums.Half.UPPER
                    : com.github.retrooper.packetevents.protocol.world.states.enums.Half.LOWER);
            state.setOpen(doorData.isOpen());
            state.setHinge(doorData.getHinge() == org.bukkit.block.data.type.Door.Hinge.LEFT
                    ? com.github.retrooper.packetevents.protocol.world.states.enums.Hinge.LEFT
                    : com.github.retrooper.packetevents.protocol.world.states.enums.Hinge.RIGHT);
        }
        if (state.hasProperty(com.github.retrooper.packetevents.protocol.world.states.type.StateValue.TYPE)) {
            if (data instanceof org.bukkit.block.data.type.Chest chestData) {
                // 双箱左右属性以真实方块数据为准（单箱 SINGLE 时用兜底类型补全）
                org.bukkit.block.data.type.Chest.Type chestType = chestData.getType();
                if (chestType == org.bukkit.block.data.type.Chest.Type.LEFT) {
                    state.setTypeData(com.github.retrooper.packetevents.protocol.world.states.enums.Type.LEFT);
                } else if (chestType == org.bukkit.block.data.type.Chest.Type.RIGHT) {
                    state.setTypeData(com.github.retrooper.packetevents.protocol.world.states.enums.Type.RIGHT);
                } else if (fallbackChestType != null) {
                    state.setTypeData(fallbackChestType);
                }
            } else if (fallbackChestType != null) {
                state.setTypeData(fallbackChestType);
            }
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
}
