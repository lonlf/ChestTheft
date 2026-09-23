package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.config.Messages;
import dev.lone.itemsadder.api.CustomStack;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.th0rgal.oraxen.api.OraxenItems;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.api.player.PlayerData;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import pers.neige.neigeitems.manager.ItemManager;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** 跨插件物品工具类：按 ID 前缀（ItemsAdder/NeigeItems/MMOItems/MythicMobs/Nexo/Oraxen/CraftEngine）取物品。 */
public final class CrossPluginItemUtil {

    /** 已告警过的"前缀|原因"，同类失败只提示一次，避免刷屏。 */
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    /** 已提示过的"自动补全命名空间"类日志。 */
    private static final Set<String> REPORTED_HINTS = ConcurrentHashMap.newKeySet();

    private CrossPluginItemUtil() {
    }

    /** 判断物品 ID 是否为外部插件物品（以已知插件前缀开头）。
     * @param id 物品 ID 字符串
     * @return 是否匹配已知插件前缀 */
    public static boolean isExternalPluginId(String id) {
        if (id == null || id.isEmpty()) return false;
        String upper = id.toUpperCase(Locale.ROOT);
        return upper.startsWith("ITEMSADDER:")
                || upper.startsWith("NEIGEITEMS:")
                || upper.startsWith("MMOITEMS:")
                || upper.startsWith("MYTHICMOBS:")
                || upper.startsWith("NEXO:")
                || upper.startsWith("ORAXEN:")
                || upper.startsWith("CRAFTENGINE:");
    }

    /** 从外部插件获取物品 ItemStack（player 部分插件需要，可为 null）。
     * @param id 物品 ID（如 "MMOItems:SWORD:example_item"、"ItemsAdder:ruby"）
     * @return 物品 ItemStack，获取失败返回 null */
    @Nullable
    public static ItemStack getItem(String id, @Nullable Player player) {
        if (id == null || id.isBlank()) return null;
        String trimmed = id.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx < 0) return null;

        String prefix = trimmed.substring(0, colonIdx).toUpperCase(Locale.ROOT);
        String rest = trimmed.substring(colonIdx + 1);

        try {
            switch (prefix) {
                case "ITEMSADDER":
                    return getItemsAdderItem(rest);
                case "NEIGEITEMS":
                    return getNeigeItemsItem(rest, player);
                case "MMOITEMS":
                    // 格式: MMOItems:<type>:<id>
                    return getMMOItemsItem(rest, player);
                case "MYTHICMOBS":
                    return getMythicMobsItem(rest);
                case "NEXO":
                    return getNexoItem(rest);
                case "ORAXEN":
                    return getOraxenItem(rest);
                case "CRAFTENGINE":
                    return getCraftEngineItem(rest);
                default:
                    return null;
            }
        } catch (Throwable t) {
            // API 版本漂移或插件内部异常：降级为"解析失败"，不冒泡到命令/事件
            reportFailure(trimmed, prefix, t);
            return null;
        }
    }

    /** 输出一次解析失败告警（同前缀同原因只提示一次）。 */
    private static void reportFailure(String id, String prefix, Throwable cause) {
        String reason = cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        if (REPORTED_FAILURES.add(prefix + "|" + reason)) {
            Bukkit.getLogger().log(Level.WARNING,
                    Messages.getLog(Messages.LOG_ITEM_EXTERNAL_API_ERROR, id, reason));
        }
    }

    @Nullable
    private static ItemStack getItemsAdderItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return null;
        CustomStack stack = CustomStack.getInstance(id);
        return stack != null ? stack.getItemStack() : null;
    }

    @Nullable
    private static ItemStack getNeigeItemsItem(String id, @Nullable Player player) {
        if (Bukkit.getPluginManager().getPlugin("NeigeItems") == null) return null;
        return ItemManager.INSTANCE.getItemStack(id, player);
    }

    /** MMOItems 格式为 {@code MMOItems:<type>:<id>}（如 MMOItems:SWORD:example_item）。 */
    @Nullable
    private static ItemStack getMMOItemsItem(String rest, @Nullable Player player) {
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null) return null;
        int typeColon = rest.indexOf(':');
        if (typeColon < 0) return null;
        String mmoType = rest.substring(0, typeColon);
        String mmoId = rest.substring(typeColon + 1);
        try {
            Type type = Type.get(mmoType);
            if (type == null) return null;
            if (player != null) {
                return MMOItems.plugin.getItem(type, mmoId, PlayerData.get(player.getUniqueId()));
            }
            return MMOItems.plugin.getItem(type, mmoId);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static ItemStack getMythicMobsItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) return null;
        return MythicBukkit.inst().getItemManager().getItemStack(id);
    }

    @Nullable
    private static ItemStack getNexoItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) return null;
        ItemBuilder builder = NexoItems.itemFromId(id);
        return builder != null ? builder.build() : null;
    }

    @Nullable
    private static ItemStack getOraxenItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("Oraxen") == null) return null;
        var item = OraxenItems.getItemById(id);
        return item != null ? item.build() : null;
    }

    /** CraftEngine 取物：byId(Key) 取定义 → buildBukkitItem()；key 缺命名空间时按物品名兜底匹配并提示完整 ID。 */
    @Nullable
    private static ItemStack getCraftEngineItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("CraftEngine") == null) return null;
        var definition = CraftEngineItems.byId(Key.of(id));
        if (definition == null) {
            definition = findCraftEngineItemByPath(id);
        }
        if (definition == null) return null;
        ItemStack stack = definition.buildBukkitItem();
        return stack == null || stack.getType().isAir() ? null : stack;
    }

    /** 在已加载物品中按物品名（path）匹配，仅配置未写命名空间时启用；同名多个取最靠前者并提示完整 ID。 */
    @Nullable
    private static BukkitItemDefinition findCraftEngineItemByPath(String path) {
        if (path.indexOf(':') >= 0) return null;
        for (Key key : CraftEngineItems.loadedItems().keySet()) {
            if (!key.value().equalsIgnoreCase(path)) continue;
            BukkitItemDefinition definition = CraftEngineItems.byId(key);
            if (definition == null) continue;
            String fullId = key.namespace() + ":" + key.value();
            if (REPORTED_HINTS.add("CRAFTENGINE|" + path)) {
                Bukkit.getLogger().log(Level.WARNING,
                        Messages.getLog(Messages.LOG_ITEM_EXTERNAL_NAMESPACE_GUESSED, path, fullId));
            }
            return definition;
        }
        return null;
    }
}

