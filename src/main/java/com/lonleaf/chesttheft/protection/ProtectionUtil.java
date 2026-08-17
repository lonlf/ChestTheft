package com.lonleaf.chesttheft.protection;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * 外部保护插件（LWC / Bolt）集成工具：检查方块是否被保护、获取保护所有者。
 * Bolt 通过 Bukkit ServicesManager 加载 API；LWC 直接获取插件实例。
 */
public class ProtectionUtil {

    private static final Logger LOGGER = Logger.getLogger(ProtectionUtil.class.getName());

    private ProtectionUtil() {}

    /** 检测方块是否被 LWC 或 Bolt 保护。 */
    public static boolean isProtected(Block block) {
        return isBoltProtected(block) || isLWCProtected(block);
    }

    /** 获取保护的所有者 UUID；无保护或无法获取时返回 null。 */
    public static UUID getOwnerUUID(Block block) {
        // Bolt 优先（现代化 API，直接返回 UUID）
        if (isBoltAvailable()) {
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
        }
        // LWC 兜底
        if (isLWCAvailable()) {
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
        }
        return null;
    }

    /** 判断玩家是否为保护所有者。 */
    public static boolean isOwner(Block block, Player player) {
        UUID owner = getOwnerUUID(block);
        return owner != null && owner.equals(player.getUniqueId());
    }

    // ==================== Bolt ====================

    private static boolean isBoltProtected(Block block) {
        if (!isBoltAvailable()) return false;
        try {
            org.popcraft.bolt.BoltAPI bolt = Bukkit.getServicesManager().load(org.popcraft.bolt.BoltAPI.class);
            return bolt != null && bolt.isProtected(block);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBoltAvailable() {
        return Bukkit.getPluginManager().getPlugin("Bolt") != null;
    }

    // ==================== LWC ====================

    private static boolean isLWCProtected(Block block) {
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
}