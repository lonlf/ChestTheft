package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ItemConfigManager {
    private final ChestTheft plugin;
    private final Map<String, ItemDefinition> definitions = new LinkedHashMap<>();
    private final Map<ItemType, String> defaultIds = new EnumMap<>(ItemType.class);

    public ItemConfigManager(ChestTheft plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        definitions.clear();
        defaultIds.clear();
        // 复制内置默认物品配置（仅在文件缺失时复制，避免 saveResource 对已存在文件输出警告）
        for (ItemType type : ItemType.values()) {
            File target = new File(plugin.getDataFolder(), "items/" + type.getConfigKey() + ".yml");
            if (!target.exists()) {
                plugin.saveResource("items/" + type.getConfigKey() + ".yml", false);
            }
        }
        File itemsDir = new File(plugin.getDataFolder(), "items");
        File[] files = itemsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            for (String id : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                ItemDefinition def = ItemDefinition.from(id, section, plugin.getLogger());
                if (def == null) {
                    continue; // type 缺失或非法，已告警
                }
                if (definitions.containsKey(id)) {
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_ITEM_DUPLICATE, id, file.getName()));
                }
                definitions.put(id, def);
                defaultIds.putIfAbsent(def.getType(), id);
            }
        }
    }

    public ItemDefinition getDefinition(String id) {
        return definitions.get(id);
    }

    /** 某类型默认物品 ID（该类型首个加载的定义）。 */
    public String getDefaultId(ItemType type) {
        return defaultIds.get(type);
    }

    public Collection<ItemDefinition> getDefinitions() {
        return definitions.values();
    }
}
