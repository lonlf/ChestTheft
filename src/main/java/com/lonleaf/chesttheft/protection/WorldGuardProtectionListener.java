package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.database.Database;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WorldGuard 区域保护集成（软依赖）：打开前临时加入区域 members，关闭/退出时移除。
 * 授权区域列表入库，崩溃后启动清理移除成员。
 */
public class WorldGuardProtectionListener extends TempAccessListener {

    public WorldGuardProtectionListener(Plugin plugin, Database database) {
        super(plugin, database);
    }

    @Override
    protected String pluginType() {
        return "worldguard";
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
    protected void revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null || extra == null || extra.isEmpty()) {
            return;
        }
        RegionManager manager = getRegionManager(block);
        if (manager == null) {
            return;
        }
        try {
            for (String id : extra.split("\u0001", -1)) {
                ProtectedRegion region = manager.getRegion(id);
                if (region != null) {
                    region.getMembers().removePlayer(playerUuid);
                }
            }
            manager.save();
        } catch (Exception e) {
            plugin.getLogger().fine("清理 WorldGuard 残留成员失败: " + e.getMessage());
        }
    }

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
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
        if (!grant.granted || Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            return;
        }
        // 仅移除本次临时添加的区域成员，避免误删玩家原本的成员身份
        WgGrant wgGrant = (WgGrant) grant;
        try {
            RegionManager manager = getRegionManager(block);
            if (manager == null) {
                return;
            }
            for (ProtectedRegion region : wgGrant.addedRegions) {
                region.getMembers().removePlayer(player.getUniqueId());
            }
            manager.save();
        } catch (Exception e) {
            plugin.getLogger().fine("撤销 WorldGuard 临时成员失败: " + e.getMessage());
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
