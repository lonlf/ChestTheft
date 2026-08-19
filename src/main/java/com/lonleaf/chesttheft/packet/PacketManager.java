package com.lonleaf.chesttheft.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;
import com.lonleaf.chesttheft.item.ItemConfigManager;
import com.lonleaf.chesttheft.item.ItemDefinition;
import com.lonleaf.chesttheft.item.ItemType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议包处理模块（PacketEvents）：为特殊物品动态注入 Lore（仅客户端可见，不改真实数据）。
 * 覆盖四类物品包避免点击/拖动/光标场景注入行丢失；版本差异见插件结构与功能说明文档。
 */
public class PacketManager implements PacketListener {

    /** 与主类 ItemTagger 的 NamespacedKey 对应的 NBT 键名 */
    private static final String NBT_ID_KEY = "chesttheft:item_id";
    private static final String NBT_LOCK_KEY = "chesttheft:paired_lock";
    /** 旧版本（1.20.5 前）PDC 在 tag 中的存储位置 */
    private static final String NBT_LEGACY_PDC = "PublicBukkitValues";

    private final ChestTheft plugin;
    private final PluginConfig config;
    private final ItemConfigManager itemConfigManager;

    public PacketManager(ChestTheft plugin, PluginConfig config, ItemConfigManager itemConfigManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemConfigManager = itemConfigManager;
        PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                handleSetSlot(event);
            } else if (event.getPacketType() == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
                // 1.20.5+ 玩家自己的生存背包槽位同步包：点击 / 拖动背包物品时服务器用此包刷新槽位，
                // 若不注入 lore，物品上的注入行会消失（丢地上再捡起走 SET_SLOT 才会恢复）。
                handleSetPlayerInventory(event);
            } else if (event.getPacketType() == PacketType.Play.Server.SET_CURSOR_ITEM) {
                // 光标物品同步包：点击物品后物品被拿起移到光标上，若不注入 lore，光标上的物品会丢失 lore
                handleSetCursorItem(event);
            } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                handleWindowItems(event);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_PACKET_LORE_FAIL, e.getMessage()));
        }
    }

    /** 玩家背包槽位更新包（ClientboundPlayerInventoryUpdate）：注入单个物品的 Lore。 */
    private void handleSetPlayerInventory(PacketSendEvent event) {
        WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
        ItemStack stack = packet.getStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemStack modified = injectLore(stack);
        if (modified != null) {
            packet.setStack(modified);
            event.markForReEncode(true);
        }
    }

    /** 光标物品同步包（ClientboundSetCursorItem）：玩家点击拿起物品时同步光标上的物品。 */
    private void handleSetCursorItem(PacketSendEvent event) {
        WrapperPlayServerSetCursorItem packet = new WrapperPlayServerSetCursorItem(event);
        ItemStack stack = packet.getStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemStack modified = injectLore(stack);
        if (modified != null) {
            packet.setStack(modified);
            event.markForReEncode(true);
        }
    }

    /** 槽位更新包：注入单个物品的 Lore。 */
    private void handleSetSlot(PacketSendEvent event) {
        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        ItemStack item = packet.getItem();
        if (item == null || item.isEmpty()) {
            return;
        }
        ItemStack modified = injectLore(item);
        if (modified != null) {
            packet.setItem(modified);
            event.markForReEncode(true);
        }
    }

    /** 窗口物品包：遍历注入整个窗口的物品列表，并处理光标携带物品（点击物品后光标上的特殊物品同样注入 lore）。 */
    private void handleWindowItems(PacketSendEvent event) {
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
        List<ItemStack> items = packet.getItems();
        List<ItemStack> modified = new ArrayList<>(items.size());
        boolean changed = false;
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                modified.add(item);
                continue;
            }
            ItemStack injected = injectLore(item);
            if (injected != null) {
                modified.add(injected);
                changed = true;
            } else {
                modified.add(item);
            }
        }
        if (changed) {
            packet.setItems(modified);
            event.markForReEncode(true);
        }
        // 光标携带物品：玩家点击物品后物品移到光标上，若此处不注入，光标物品会丢失 lore
        if (packet.getCarriedItem().isPresent() && !packet.getCarriedItem().get().isEmpty()) {
            ItemStack carried = packet.getCarriedItem().get();
            ItemStack injected = injectLore(carried);
            if (injected != null) {
                packet.setCarriedItem(injected);
                event.markForReEncode(true);
            }
        }
    }

    /**
     * 为特殊物品追加 Lore 展示信息，非特殊物品返回 null；直接读 NBT（包内 PDC 不可用）。
     */
    private ItemStack injectLore(ItemStack item) {
        NBTCompound legacyTag = item.getOrCreateTag();
        // 1.20.5+ 组件化物品：PDC 位于 minecraft:custom_data 组件中
        NBTCompound customData = item.getComponent(ComponentTypes.CUSTOM_DATA).orElse(null);
        String id = readNbtString(legacyTag, customData, NBT_ID_KEY);
        if (config.isDebug()) {
            plugin.getLogger().info(Messages.getLog(Messages.LOG_PACKET_DEBUG, legacyTag, customData, id));
        }
        if (id == null) {
            return null;
        }
        ItemDefinition def = itemConfigManager.getDefinition(id);
        if (def == null) {
            return null;
        }
        ItemType type = def.getType();

        org.bukkit.inventory.ItemStack bukkitItem = SpigotConversionUtil.toBukkitItemStack(item).clone();
        ItemMeta meta = bukkitItem.getItemMeta();
        if (meta == null) {
            return null;
        }
        // 类型名本地化（key / lock / picker 按当前语言显示）
        String typeName = switch (type) {
            case KEY -> Messages.TYPE_KEY;
            case LOCK -> Messages.TYPE_LOCK;
            case PICKER -> Messages.TYPE_PICKER;
        };
        String typeLine = Messages.get(Messages.LORE_TYPE, typeName);
        String notPairedLine = Messages.get(Messages.LORE_NOT_PAIRED);
        // 幂等：移除已残留的旧注入行（空行、类型行、配对/未配对行），防止被污染的物品重复追加
        String pairedPrefix = Messages.get(Messages.LORE_PAIRED, "");
        List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
        lore.removeIf(line -> line.isEmpty()
                || line.equals(typeLine)
                || line.equals(notPairedLine)
                || line.startsWith(pairedPrefix));
        lore.add("");
        lore.add(typeLine);
        if (type == ItemType.KEY) {
            String paired = readNbtString(legacyTag, customData, NBT_LOCK_KEY);
            if (paired != null) {
                lore.add(Messages.get(Messages.LORE_PAIRED, paired));
            } else {
                lore.add(notPairedLine);
            }
        }
        meta.setLore(lore);
        bukkitItem.setItemMeta(meta);
        ItemStack modified = SpigotConversionUtil.fromBukkitItemStack(bukkitItem);
        // 1.20.5+ 组件化物品：fromBukkit 转换可能丢失 custom_data（PDC），手动恢复
        if (customData != null) {
            modified.setComponent(ComponentTypes.CUSTOM_DATA, customData);
        }
        return modified;
    }

    /** 兼容不同版本从 NBT 读取 PDC：custom_data 组件内为 PublicBukkitValues 包装或 namespace 嵌套，旧版位于 tag。 */
    private String readNbtString(NBTCompound legacyTag, NBTCompound customData, String key) {
        if (customData != null) {
            // 结构一：custom_data 内嵌套 namespace（{ chesttheft: { item_id: "..." } }）
            String value = readNamespaced(customData, key);
            if (value != null) {
                return value;
            }
            // 结构二：custom_data 内直接以完整命名空间键存储（{"chesttheft:item_id": "..."}）
            NBTString direct = customData.getStringTagOrNull(key);
            if (direct != null) {
                return direct.getValue();
            }
            // 结构三：custom_data 内为 PublicBukkitValues 包装（{PublicBukkitValues: {"chesttheft:item_id": "..."}}）
            NBTCompound bukkitValues = customData.getCompoundTagOrNull(NBT_LEGACY_PDC);
            if (bukkitValues != null) {
                NBTString s = bukkitValues.getStringTagOrNull(key);
                if (s != null) {
                    return s.getValue();
                }
            }
        }
        if (legacyTag == null) {
            return null;
        }
        // 结构四：旧版本（1.20.5 前）tag 中的 PublicBukkitValues
        NBTCompound bukkitValues = legacyTag.getCompoundTagOrNull(NBT_LEGACY_PDC);
        if (bukkitValues != null) {
            NBTString s = bukkitValues.getStringTagOrNull(key);
            if (s != null) {
                return s.getValue();
            }
        }
        NBTString direct = legacyTag.getStringTagOrNull(key);
        return direct == null ? null : direct.getValue();
    }

    /** custom_data 中的 PDC 键形如 chesttheft:item_id，对应嵌套结构 { chesttheft: { item_id: "..." } } */
    private String readNamespaced(NBTCompound parent, String key) {
        int idx = key.indexOf(':');
        if (idx <= 0 || idx >= key.length() - 1) {
            return null;
        }
        NBTCompound ns = parent.getCompoundTagOrNull(key.substring(0, idx));
        if (ns == null) {
            return null;
        }
        NBTString s = ns.getStringTagOrNull(key.substring(idx + 1));
        return s == null ? null : s.getValue();
    }
}
