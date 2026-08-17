package com.lonleaf.chesttheft.item;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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
            String resPath = type.getConfigPath();
            File target = new File(plugin.getDataFolder(), resPath);
            if (!target.exists()) {
                plugin.saveResource(resPath, false);
            }
        }
        // 递归扫描 items/ 下所有 yml（lock/key 在子文件夹，可多文件扩展）
        List<File> files = new ArrayList<>();
        collectYml(new File(plugin.getDataFolder(), "items"), files);
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

    /** 递归收集目录下所有 yml 文件，按文件名排序（保证加载顺序稳定）。 */
    private void collectYml(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isDirectory()) {
                collectYml(file, out);
            } else if (file.getName().endsWith(".yml")) {
                out.add(file);
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
