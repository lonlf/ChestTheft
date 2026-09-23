package com.lonleaf.chesttheft.lootchest;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.TemplateFiles;
import com.lonleaf.chesttheft.item.ItemManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 战利品箱配置加载（lootchest/ 文件夹全部 yml）：档案 ID 为主键，default 为回退档案。 */
public class LootChestConfigManager {
    private final ChestTheft plugin;
    private final ItemManager itemManager;
    private final File lootDir;
    private final Map<String, LootChestProfile> profiles = new LinkedHashMap<>();

    public LootChestConfigManager(ChestTheft plugin, ItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.lootDir = new File(plugin.getDataFolder(), "lootchest");
        saveDefault();
        load();
    }

    /** 保存默认模板 lootchest.yml（仅当 lootchest 目录下没有任何 yml 时创建；注释语言随当前语言）。 */
    private void saveDefault() {
        File[] files = lootDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            try {
                String lang = plugin.getConfig().getString("language", "");
                if (!TemplateFiles.saveTemplate(plugin, lang, "lootchest.yml",
                        new File(lootDir, "lootchest.yml"))) {
                    throw new IllegalStateException("Embedded template not found in jar: templates/*/lootchest.yml");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save default lootchest/lootchest.yml", e);
            }
        }
    }

    public void reload() {
        load();
    }

    public void load() {
        profiles.clear();
        File[] files = lootDir.listFiles((dir, name) -> name.endsWith(".yml"));
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
                LootChestProfile profile = LootChestProfile.from(id, section, plugin.getLogger());
                if (profiles.containsKey(id)) {
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_LOOT_CHEST_DUPLICATE, id, file.getName()));
                }
                profiles.put(id, profile);
            }
        }
    }

    /** 按实体标识符遍历所有档案的 drops 映射进行匹配；未匹配返回 null。 */
    public LootChestProfile getProfileByEntity(String entityIdentifier) {
        for (LootChestProfile profile : profiles.values()) {
            if (profile.hasDrop(entityIdentifier)) {
                return profile;
            }
        }
        return null;
    }

    /** 按档案 ID 精确匹配（命令使用），不存在时返回 null。 */
    public LootChestProfile getProfileById(String id) {
        return profiles.get(id);
    }

    /** 全部档案 ID（命令补全使用）。 */
    public List<String> getProfileIds() {
        return new ArrayList<>(profiles.keySet());
    }

    /** 构建箱内物品所需的物品管理器（items/ 文件夹物品定义）。 */
    public ItemManager getItemManager() {
        return itemManager;
    }
}
