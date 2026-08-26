package com.lonleaf.chesttheft.protection;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * 外部保护插件检测工具：按插件提供"是否受保护"与"保护所有者"查询。
 * 各保护适配器（{@link ProtectionAdapter}）复用这些方法完成自己的检测，
 * 统一判定入口为 {@link ProtectionManager}。
 */
public class ProtectionUtil {

    private static final Logger LOGGER = Logger.getLogger(ProtectionUtil.class.getName());

    private ProtectionUtil() {}

    // ==================== Bolt ====================

    /** 方块是否受 Bolt 保护。 */
    public static boolean isBoltProtected(Block block) {
        if (!isBoltAvailable()) return false;
        try {
            org.popcraft.bolt.BoltAPI bolt = Bukkit.getServicesManager().load(org.popcraft.bolt.BoltAPI.class);
            return bolt != null && bolt.isProtected(block);
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 Bolt 保护所有者 UUID；无保护或查询失败返回 null。 */
    public static UUID boltOwner(Block block) {
        if (!isBoltAvailable()) return null;
        try {
            org.popcraft.bolt.BoltAPI bolt = Bukkit.getServicesManager().load(org.popcraft.bolt.BoltAPI.class);
            if (bolt != null) {
                org.popcraft.bolt.protection.Protection protection = bolt.findProtection(block);
                if (protection != null) {
                    return protection.getOwner();
                }
            }
        } catch (Exception e) {
            LOGGER.fine("Bolt owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isBoltAvailable() {
        return Bukkit.getPluginManager().getPlugin("Bolt") != null;
    }

    // ==================== LWC ====================

    /** 方块是否受 LWC 保护。 */
    public static boolean isLWCProtected(Block block) {
        if (!isLWCAvailable()) return false;
        try {
            com.griefcraft.lwc.LWCPlugin lwcPlugin = (com.griefcraft.lwc.LWCPlugin) Bukkit.getPluginManager().getPlugin("LWC");
            if (lwcPlugin != null) {
                com.griefcraft.lwc.LWC lwc = lwcPlugin.getLWC();
                return lwc.findProtection(block) != null;
            }
        } catch (Exception e) {
        }
        return false;
    }

    /** 获取 LWC 保护所有者 UUID；无保护或查询失败返回 null。 */
    public static UUID lwcOwner(Block block) {
        if (!isLWCAvailable()) return null;
        try {
            com.griefcraft.lwc.LWCPlugin lwcPlugin = (com.griefcraft.lwc.LWCPlugin) Bukkit.getPluginManager().getPlugin("LWC");
            if (lwcPlugin != null) {
                com.griefcraft.lwc.LWC lwc = lwcPlugin.getLWC();
                com.griefcraft.model.Protection protection = lwc.findProtection(block);
                if (protection != null) {
                    return resolveLWCOwner(protection);
                }
            }
        } catch (Exception e) {
            LOGGER.fine("LWC owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isLWCAvailable() {
        return Bukkit.getPluginManager().getPlugin("LWC") != null;
    }

    /**
     * 解析 LWC 保护所有者 UUID：getOwner() 可能返回玩家名或 UUID 字符串，需兼容处理。
     * 参数用 Object 接收（方法签名中的 LWC 类型在类验证期解析，未装 LWC 时会导致类加载失败）。
     */
    private static UUID resolveLWCOwner(Object protectionObj) {
        com.griefcraft.model.Protection protection = (com.griefcraft.model.Protection) protectionObj;
        String owner = protection.getOwner();
        if (owner == null || owner.isEmpty()) return null;
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException ignored) {}
        // 回退为玩家名查询：优先在线玩家（无副作用），离线查询仅对已登录过的名字接受，
        // 避免 getOfflinePlayer 为从未登入的名字写入离线玩家记录
        try {
            Player online = Bukkit.getPlayerExact(owner);
            if (online != null) {
                return online.getUniqueId();
            }
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(owner);
            if (offline.hasPlayedBefore()) {
                return offline.getUniqueId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== Residence ====================

    /** 方块是否位于 Residence 领地内。 */
    public static boolean isResidenceProtected(Block block) {
        if (!isResidenceAvailable()) return false;
        try {
            com.bekvon.bukkit.residence.protection.ClaimedResidence res =
                    com.bekvon.bukkit.residence.api.ResidenceApi.getResidenceManager().getByLoc(block.getLocation());
            return res != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 Residence 领地所有者 UUID；不在领地内返回 null。 */
    public static UUID residenceOwner(Block block) {
        if (!isResidenceAvailable()) return null;
        try {
            com.bekvon.bukkit.residence.protection.ClaimedResidence res =
                    com.bekvon.bukkit.residence.api.ResidenceApi.getResidenceManager().getByLoc(block.getLocation());
            if (res != null) {
                return res.getOwnerUUID();
            }
        } catch (Exception e) {
            LOGGER.fine("Residence owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isResidenceAvailable() {
        return Bukkit.getPluginManager().getPlugin("Residence") != null;
    }

    // ==================== Dominion ====================

    /** 方块是否位于 Dominion 领地内。 */
    public static boolean isDominionProtected(Block block) {
        if (!isDominionAvailable()) return false;
        try {
            cn.lunadeer.dominion.api.DominionAPI api = cn.lunadeer.dominion.api.DominionAPI.getInstance();
            return api != null && api.getDominion(block.getLocation()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 Dominion 领地所有者 UUID；不在领地内返回 null。 */
    public static UUID dominionOwner(Block block) {
        if (!isDominionAvailable()) return null;
        try {
            cn.lunadeer.dominion.api.DominionAPI api = cn.lunadeer.dominion.api.DominionAPI.getInstance();
            if (api != null) {
                cn.lunadeer.dominion.api.dtos.DominionDTO dominion = api.getDominion(block.getLocation());
                if (dominion != null) {
                    return dominion.getOwner();
                }
            }
        } catch (Exception e) {
            LOGGER.fine("Dominion owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isDominionAvailable() {
        return Bukkit.getPluginManager().getPlugin("Dominion") != null;
    }

    // ==================== GriefDefender ====================

    /** 方块是否位于 GriefDefender 领地（非荒野）内。 */
    public static boolean isGriefDefenderProtected(Block block) {
        if (!isGriefDefenderAvailable()) return false;
        try {
            com.griefdefender.api.claim.Claim claim =
                    com.griefdefender.api.GriefDefender.getCore().getClaimAt(block.getLocation());
            return claim != null && !claim.isWilderness();
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 GriefDefender 领地所有者 UUID；在荒野或查询失败返回 null。 */
    public static UUID griefDefenderOwner(Block block) {
        if (!isGriefDefenderAvailable()) return null;
        try {
            com.griefdefender.api.claim.Claim claim =
                    com.griefdefender.api.GriefDefender.getCore().getClaimAt(block.getLocation());
            if (claim != null && !claim.isWilderness()) {
                return claim.getOwnerUniqueId();
            }
        } catch (Exception e) {
            LOGGER.fine("GriefDefender owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isGriefDefenderAvailable() {
        return Bukkit.getPluginManager().getPlugin("GriefDefender") != null;
    }

    // ==================== Towny ====================

    /** 方块是否位于 Towny 城镇领地（地块）内。 */
    public static boolean isTownyProtected(Block block) {
        if (!isTownyAvailable()) return false;
        try {
            return com.palmergames.bukkit.towny.TownyAPI.getInstance().getTownBlock(block.getLocation()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 Towny 城镇所有者（市长）UUID；不在城镇地块内返回 null。 */
    public static UUID townyOwner(Block block) {
        if (!isTownyAvailable()) return null;
        try {
            com.palmergames.bukkit.towny.object.TownBlock townBlock =
                    com.palmergames.bukkit.towny.TownyAPI.getInstance().getTownBlock(block.getLocation());
            if (townBlock != null) {
                com.palmergames.bukkit.towny.object.Town town = townBlock.getTownOrNull();
                if (town != null && town.getMayor() != null) {
                    return town.getMayor().getUUID();
                }
            }
        } catch (Exception e) {
            LOGGER.fine("Towny owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isTownyAvailable() {
        return Bukkit.getPluginManager().getPlugin("Towny") != null;
    }

    // ==================== WorldGuard ====================

    /**
     * 方块是否位于 WorldGuard 区域（region）内。
     * 排除全局区域 __global__（覆盖整个世界的默认区域，无实际保护意义）。
     */
    public static boolean isWorldGuardProtected(Block block) {
        if (!isWorldGuardAvailable()) return false;
        try {
            com.sk89q.worldguard.protection.regions.RegionQuery query =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            com.sk89q.worldguard.protection.ApplicableRegionSet set =
                    query.getApplicableRegions(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(block.getLocation()));
            for (com.sk89q.worldguard.protection.regions.ProtectedRegion region : set.getRegions()) {
                if (!isGlobalRegion(region)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** 获取 WorldGuard 区域所有者 UUID（遍历所在区域，取第一个有所有者的区域）；无则返回 null。 */
    public static UUID worldGuardOwner(Block block) {
        if (!isWorldGuardAvailable()) return null;
        try {
            com.sk89q.worldguard.protection.regions.RegionQuery query =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            com.sk89q.worldguard.protection.ApplicableRegionSet set =
                    query.getApplicableRegions(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(block.getLocation()));
            for (com.sk89q.worldguard.protection.regions.ProtectedRegion region : set.getRegions()) {
                if (isGlobalRegion(region)) continue;
                java.util.Set<UUID> owners = region.getOwners().getUniqueIds();
                if (!owners.isEmpty()) {
                    return owners.iterator().next();
                }
            }
        } catch (Exception e) {
            LOGGER.fine("WorldGuard owner lookup failed: " + e.getMessage());
        }
        return null;
    }

    /** WorldGuard 全局区域（__global__）判断。 */
    private static boolean isGlobalRegion(com.sk89q.worldguard.protection.regions.ProtectedRegion region) {
        return "__global__".equals(region.getId());
    }

    private static boolean isWorldGuardAvailable() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    // ==================== NoBuildPlus ====================

    /**
     * 方块所在世界是否被 NoBuildPlus 保护（启用了容器/交互/破坏相关 flag）。
     * NoBuildPlus 为世界级 flag 保护，无区域与所有者概念，只参与"受保护"判定，不参与所有者查询。
     */
    public static boolean isNoBuildPlusProtected(Block block) {
        if (!isNoBuildPlusAvailable()) return false;
        try {
            String worldName = block.getWorld().getName();
            p1xel.nobuildplus.api.NBPAPI api = p1xel.nobuildplus.NoBuildPlus.getInstance().getAPI();
            if (api == null || !api.isWorldEnabled(worldName)) return false;
            return api.canExecute(worldName, p1xel.nobuildplus.Flags.container)
                    || api.canExecute(worldName, p1xel.nobuildplus.Flags.use)
                    || api.canExecute(worldName, p1xel.nobuildplus.Flags.destroy);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNoBuildPlusAvailable() {
        return Bukkit.getPluginManager().getPlugin("NoBuildPlus") != null;
    }
}
