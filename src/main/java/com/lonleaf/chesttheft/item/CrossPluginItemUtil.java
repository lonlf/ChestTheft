package com.lonleaf.chesttheft.item;

import dev.lone.itemsadder.api.CustomStack;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.th0rgal.oraxen.api.OraxenItems;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.api.player.PlayerData;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.util.Key;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import pers.neige.neigeitems.manager.ItemManager;

import java.util.Locale;

/** 跨插件物品工具类，从其他物品插件获取物品 ItemStack（支持 ItemsAdder/NeigeItems/MMOItems/MythicMobs/Nexo/Oraxen/CraftEngine 前缀）。 */
public final class CrossPluginItemUtil {

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

    @Nullable
    private static ItemStack getCraftEngineItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("CraftEngine") == null) return null;
        var key = Key.of(id);
        var item = net.momirealms.craftengine.core.item.Item.byId(key);
        if (item == null || item.isEmpty()) return null;
        var bukkitStack = ItemStackUtils.getBukkitStack(item);
        if (ItemStackUtils.isEmpty(bukkitStack)) return null;
        return bukkitStack;
    }
}