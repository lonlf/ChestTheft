package com.lonleaf.chesttheft.protection;

import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.TrustTypes;
import com.griefdefender.api.event.CreateClaimEvent;
import com.griefdefender.lib.flowpowered.math.vector.Vector3i;
import com.griefdefender.lib.kyori.event.EventSubscription;
import com.lonleaf.chesttheft.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * GriefDefender 领地保护适配器（软依赖）：领地创建自动卸锁 + 打开前事件内临时授予 CONTAINER 信任。
 * 信任为持久化写入（GD 库），授权前落库，崩溃后启动清理移除。
 */
public class GriefDefenderAdapter extends TempAccessAdapter {

    /** 领地创建事件订阅（注销时用于退订）。 */
    private EventSubscription subscription;

    public GriefDefenderAdapter(Plugin plugin, Database database) {
        super(plugin, database);
    }

    @Override
    public String pluginType() {
        return "griefdefender";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("GriefDefender") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isGriefDefenderProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.griefDefenderOwner(block);
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        return "";
    }

    @Override
    protected void revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (!isActive()) {
            return;
        }
        Claim claim = GriefDefender.getCore().getClaimAt(block.getLocation());
        if (claim == null || claim.isWilderness()) {
            return;
        }
        claim.removeUserTrust(playerUuid, TrustTypes.CONTAINER);
    }

    /** 注册并订阅 GriefDefender 领地创建事件（仅 GriefDefender 插件存在时）。 */
    @Override
    public void register(Consumer<Block> protectionCreatedHandler) {
        super.register(protectionCreatedHandler);
        if (isActive() && subscription == null) {
            subscription = GriefDefender.getEventManager().getBus()
                    .subscribe(CreateClaimEvent.Post.class, this::onClaimCreated);
        }
    }

    /** 退订领地创建事件监听并清理临时授权。 */
    @Override
    public void unregister() {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        cleanup();
    }

    // ==================== 打开容器：临时授予 CONTAINER 信任 ====================

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (!isActive()) {
            return null;
        }
        Claim claim = GriefDefender.getCore().getClaimAt(block.getLocation());
        if (claim == null || claim.isWilderness()) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        // 领地所有者可直接打开
        if (claim.getOwnerUniqueId().equals(uuid)) {
            return null;
        }
        // 已有容器或更高权限的信任（BUILDER 隐含容器权限）时无需授权
        if (claim.getUserTrusts(TrustTypes.CONTAINER).contains(uuid)
                || claim.getUserTrusts(TrustTypes.BUILDER).contains(uuid)
                || claim.getUserTrusts(TrustTypes.MANAGER).contains(uuid)) {
            return null;
        }
        return new TempGrant(true);
    }

    @Override
    protected void apply(Block block, Player player, TempGrant grant) {
        Claim claim = GriefDefender.getCore().getClaimAt(block.getLocation());
        if (claim == null || claim.isWilderness()) {
            throw new IllegalStateException("领地消失，无法授予容器信任");
        }
        claim.addUserTrust(player.getUniqueId(), TrustTypes.CONTAINER);
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        if (!grant.granted || !isActive()) {
            return;
        }
        Claim claim = GriefDefender.getCore().getClaimAt(block.getLocation());
        if (claim == null || claim.isWilderness()) {
            return;
        }
        claim.removeUserTrust(player.getUniqueId(), TrustTypes.CONTAINER);
    }

    // ==================== 领地创建后自动卸锁 ====================

    /**
     * 领地创建成功（Post 阶段）后延迟检查区域内上锁箱子并自动卸锁。
     * 回调线程不保证为主线程，因此调度回主线程执行扫描。
     */
    private void onClaimCreated(CreateClaimEvent.Post event) {
        Claim claim = event.getClaim();
        if (claim == null || claim.isWilderness()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                scanClaimForLockedChests(claim);
            } catch (Exception e) {
                plugin.getLogger().fine("GriefDefender claim creation auto-unlock check failed: " + e.getMessage());
            }
        });
    }

    /**
     * 扫描领地内所有已加载区块中的箱子，对已上锁的箱子执行自动卸锁回调。
     * 仅检查已加载的区块（未加载的区块说明无活跃玩家，其内的箱子暂不处理）。
     */
    private void scanClaimForLockedChests(Claim claim) {
        World world = Bukkit.getWorld(claim.getWorldUniqueId());
        if (world == null) {
            world = Bukkit.getWorld(claim.getWorldName());
        }
        if (world == null) return;
        // GriefDefender 边界为闭区间 [min, max]，两个角方块均属于领地
        Vector3i min = claim.getLesserBoundaryCorner();
        Vector3i max = claim.getGreaterBoundaryCorner();
        int minY = Math.max(min.getY(), world.getMinHeight());
        int maxY = Math.min(max.getY(), world.getMaxHeight() - 1);
        for (Vector3i chunkPos : claim.getChunkPositions()) {
            int cx = chunkPos.getX();
            int cz = chunkPos.getZ();
            if (!world.isChunkLoaded(cx, cz)) continue;
            Chunk chunk = world.getChunkAt(cx, cz);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Block block = chunk.getBlock(x, y, z);
                        // 遍历的区块即领地所属区块，此处仅剔除边界区块中越界部分
                        if (!inClaim(min, max, block.getX(), y, block.getZ())) continue;
                        if (isChest(block)) {
                            // protectionCreatedHandler 内部会通过 chestService.isLocked() 判断，
                            // 仅对上锁的箱子执行自动卸锁
                            protectionCreatedHandler.accept(block);
                        }
                    }
                }
            }
        }
    }

    /** 判断坐标是否位于领地内（闭区间 [min, max]）。 */
    private boolean inClaim(Vector3i min, Vector3i max, int x, int y, int z) {
        return x >= min.getX() && x <= max.getX()
                && y >= min.getY() && y <= max.getY()
                && z >= min.getZ() && z <= max.getZ();
    }

    /** 判断方块是否为箱子（普通箱子或陷阱箱）。 */
    private boolean isChest(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }
}
