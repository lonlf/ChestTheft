package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.database.Database;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WorldGuard 区域保护适配器（软依赖）：打开前事件内临时加入区域 members，收回（下一 tick）时移除。
 * members 为持久化写入（区域文件），授权前落库（记录区域 ID 列表），崩溃后启动清理移除成员。
 */
public class WorldGuardAdapter extends TempAccessAdapter {

    public WorldGuardAdapter(Plugin plugin, Database database) {
        super(plugin, database);
    }

    @Override
    public String pluginType() {
        return "worldguard";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isWorldGuardProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.worldGuardOwner(block);
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        WgGrant wgGrant = (WgGrant) grant;
        StringBuilder sb = new StringBuilder();
        for (ProtectedRegion region : wgGrant.addedRegions) {
            if (sb.length() > 0) {
                sb.append('\u0001');
            }
            sb.append(region.getId());
        }
        return sb.toString();
    }

    @Override
    protected boolean revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (!isActive() || extra == null || extra.isEmpty()) {
            return false;
        }
        RegionManager manager = getRegionManager(block);
        if (manager == null) {
            // 区域管理器不可用：无法确认恢复，保留记录下次重试
            return false;
        }
        for (String id : extra.split("\u0001", -1)) {
            ProtectedRegion region = manager.getRegion(id);
            if (region != null) {
                region.getMembers().removePlayer(playerUuid);
            }
        }
        try {
            manager.save();
        } catch (StorageException e) {
            // 保存失败：恢复未持久化，无法确认成功，返回 false 保留记录下次启动重试
            return false;
        }
        return true;
    }

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (!isActive()) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        RegionManager manager = getRegionManager(block);
        if (manager == null) {
            return null;
        }
        List<ProtectedRegion> added = new ArrayList<>();
        ApplicableRegionSet set = manager.getApplicableRegions(BlockVector3.at(block.getX(), block.getY(), block.getZ()));
        for (ProtectedRegion region : set.getRegions()) {
            // 全局区域 __global__ 无实际保护意义；已是成员的区域无需重复添加
            if ("__global__".equals(region.getId()) || region.getMembers().contains(uuid)) {
                continue;
            }
            added.add(region);
        }
        return added.isEmpty() ? null : new WgGrant(added);
    }

    @Override
    protected void apply(Block block, Player player, TempGrant grant) {
        WgGrant wgGrant = (WgGrant) grant;
        UUID uuid = player.getUniqueId();
        RegionManager manager = getRegionManager(block);
        if (manager == null) {
            return;
        }
        try {
            for (ProtectedRegion region : wgGrant.addedRegions) {
                region.getMembers().addPlayer(uuid);
            }
            manager.save();
        } catch (Exception e) {
            // 写入失败：回滚已添加的成员，避免授权残留
            for (ProtectedRegion region : wgGrant.addedRegions) {
                region.getMembers().removePlayer(uuid);
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        if (!grant.granted || !isActive()) {
            return;
        }
        // 仅移除本次临时添加的区域成员，避免误删玩家原本的成员身份；
        // 撤销失败（区域管理器不可用/保存失败）异常上抛，由基类 revokeGrant 判定失败并保留记录供启动清理重试
        WgGrant wgGrant = (WgGrant) grant;
        RegionManager manager = getRegionManager(block);
        if (manager == null) {
            throw new IllegalStateException("WorldGuard region manager unavailable at " + block.getLocation());
        }
        for (ProtectedRegion region : wgGrant.addedRegions) {
            region.getMembers().removePlayer(player.getUniqueId());
        }
        try {
            manager.save();
        } catch (StorageException e) {
            // 保存失败视为撤销失败：异常上抛，由基类 revokeGrant 判定失败并保留记录供启动清理重试
            throw new IllegalStateException("Failed to save WorldGuard regions after revoke at " + block.getLocation(), e);
        }
    }

    /** 获取方块所在世界的区域管理器。 */
    private RegionManager getRegionManager(Block block) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(block.getWorld()));
    }

    /** WorldGuard 临时授权记录：携带本次添加成员的区域（撤销时仅移除这些区域）。 */
    private static final class WgGrant extends TempGrant {
        final List<ProtectedRegion> addedRegions;

        WgGrant(List<ProtectedRegion> addedRegions) {
            super(true);
            this.addedRegions = addedRegions;
        }
    }
}
