package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ItemManager {
    private final ItemTagger tagger;
    private final ItemConfigManager configManager;
    private final Set<String> reportedExternalFailures = ConcurrentHashMap.newKeySet();

    public ItemManager(ItemTagger tagger, ItemConfigManager configManager) {
        this.tagger = tagger;
        this.configManager = configManager;
    }

    /** 按物品 ID 构建特殊物品，ID 不存在时返回 null。 */
    public ItemStack create(String id, int amount) {
        ItemDefinition def = configManager.getDefinition(id);
        if (def == null) {
            return null;
        }
        ItemStack item = createBase(def, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (def.getName() != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', def.getName()));
            }
            if (def.getCustomModelData() != null) {
                meta.setCustomModelData(def.getCustomModelData());
            }
            if (!def.getLore().isEmpty()) {
                meta.setLore(def.getLore().stream()
                        .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                        .collect(Collectors.toList()));
            }
            item.setItemMeta(meta);
        }
        tagger.tag(item, def.getId());
        return item;
    }

    /** 构建物品基底：material 为外部插件物品时按其原始 NBT 构建，解析失败回退 {@link ItemDefinition#getMaterial()}。 */
    private ItemStack createBase(ItemDefinition def, int amount) {
        String externalId = def.getExternalId();
        if (externalId != null) {
            ItemStack external = CrossPluginItemUtil.getItem(externalId, null);
            if (external != null) {
                // 克隆后再改动，避免污染外部插件返回的缓存实例
                ItemStack base = external.clone();
                base.setAmount(amount);
                return base;
            }
            // 解析失败时补一条运行期告警（同一物品一次），否则管理员只看到物品变成兜底材质而不知原因
            if (reportedExternalFailures.add(def.getId())) {
                Bukkit.getLogger().log(Level.WARNING, Messages.getLog(Messages.LOG_ITEM_EXTERNAL_UNRESOLVED,
                        def.getId(), externalId, def.getMaterial()));
            }
        }
        return new ItemStack(def.getMaterial(), amount);
    }

    /** 物品定义（供命令诊断），未定义返回 null。 */
    public ItemDefinition getDefinition(String id) {
        return configManager.getDefinition(id);
    }

    /** 构建某类型的默认物品（该类型首个加载的定义），无定义时返回 null。 */
    public ItemStack createDefault(ItemType type, int amount) {
        String id = configManager.getDefaultId(type);
        return id == null ? null : create(id, amount);
    }

    /** 读取特殊物品 ID，非特殊物品返回 null。 */
    public String getId(ItemStack item) {
        return tagger.getId(item);
    }

    /** 通过 ID 解析物品类型，非特殊物品或定义缺失时返回 null。 */
    public ItemType getType(ItemStack item) {
        String id = tagger.getId(item);
        if (id == null) {
            return null;
        }
        ItemDefinition def = configManager.getDefinition(id);
        return def == null ? null : def.getType();
    }

    public boolean isType(ItemStack item, ItemType type) {
        return type == getType(item);
    }

    /** 读取物品定义的锁等级，非特殊物品或定义缺失时返回 0。 */
    public int getLevel(ItemStack item) {
        String id = tagger.getId(item);
        if (id == null) {
            return 0;
        }
        ItemDefinition def = configManager.getDefinition(id);
        return def == null ? 0 : def.getLevel();
    }

    /** 读取锁物品定义的 give-key（上锁后自动给予的配对钥匙 ID），未配置或物品无定义时返回 null。 */
    public String getGiveKey(ItemStack item) {
        String id = tagger.getId(item);
        if (id == null) {
            return null;
        }
        ItemDefinition def = configManager.getDefinition(id);
        return def == null ? null : def.getGiveKey();
    }

    public void tag(ItemStack item, String id) {
        tagger.tag(item, id);
    }

    public void tagAsDefault(ItemStack item, ItemType type) {
        String id = configManager.getDefaultId(type);
        if (id != null) {
            tagger.tag(item, id);
        }
    }

    public void setPairedLock(ItemStack item, BlockLocation location, String token) {
        tagger.setPairedLock(item, location, token);
    }

    /** 清除钥匙的配对信息（卸锁后恢复未配对状态）。 */
    public void clearPairedLock(ItemStack item) {
        tagger.clearPairedLock(item);
    }

    /** 读取钥匙配对的锁凭证，旧钥匙或无凭证返回 null。 */
    public String getPairedToken(ItemStack item) {
        return tagger.getPairedToken(item);
    }

    /** 读取钥匙配对的锁位置，未配对返回 null。 */
    public BlockLocation getPairedLock(ItemStack item) {
        return tagger.getPairedLock(item);
    }

    public boolean isPairedTo(ItemStack item, BlockLocation location) {
        return tagger.isPairedTo(item, location);
    }

    /** 解析锁物品定义中配置的触发器 id 列表：引用形式取原始 id，内嵌定义取临时 id（def:<物品ID>:<触发器键>），未配置时返回 null。 */
    public List<String> resolveLockTriggerIds(ItemStack item) {
        String id = tagger.getId(item);
        if (id == null) {
            return null;
        }
        ItemDefinition def = configManager.getDefinition(id);
        if (def == null || def.getTriggers() == null) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (String key : def.getTriggers().getKeys(false)) {
            if (def.getTriggers().get(key) instanceof List<?> list) {
                list.forEach(raw -> ids.add(String.valueOf(raw)));
            } else {
                ids.add("def:" + id + ":" + key);
            }
        }
        return ids;
    }

    /** 写入锁物品的触发器配置标签。 */
    public void setLockTrigger(ItemStack item, String data) {
        tagger.setLockTrigger(item, data);
    }

    /** 读取锁物品的触发器配置标签，未配置时返回 null。 */
    public String getLockTrigger(ItemStack item) {
        return tagger.getLockTrigger(item);
    }
}
