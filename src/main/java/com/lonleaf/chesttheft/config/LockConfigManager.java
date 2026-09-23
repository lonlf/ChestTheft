package com.lonleaf.chesttheft.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 不同等级锁的小游戏配置（gamelevel 文件夹下全部 yml）：数字等级 → GameConfig；等级 0 定义时覆盖默认配置。 */
public class LockConfigManager {
    private final JavaPlugin plugin;
    private final File gameLevelDir;
    private final Map<Integer, GameConfig> configs = new HashMap<>();
    /** 默认配置（config.yml 的 game 小节），配置文件未定义 0 级时的兜底。 */
    private volatile GameConfig defaultConfig;

    public LockConfigManager(JavaPlugin plugin, GameConfig defaultConfig) {
        this.plugin = plugin;
        this.gameLevelDir = new File(plugin.getDataFolder(), "gamelevel");
        this.defaultConfig = defaultConfig;
        saveDefault();
        load();
    }

    /** reload 时更新默认配置（等级 0 未在配置文件中定义时的兜底）。 */
    public void updateDefaultConfig(GameConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    /** 保存默认模板 locklevel.yml（仅当 gamelevel 目录下没有任何 yml 时创建；注释语言随当前语言）。 */
    private void saveDefault() {
        File[] files = gameLevelDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            try {
                String lang = plugin.getConfig().getString("language", "");
                if (!TemplateFiles.saveTemplate(plugin, lang, "locklevel.yml",
                        new File(gameLevelDir, "locklevel.yml"))) {
                    throw new IllegalStateException("Embedded template not found in jar: templates/*/locklevel.yml");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save default gamelevel/locklevel.yml", e);
            }
        }
    }

    public void load() {
        configs.clear();
        File[] files = gameLevelDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            loadFile(file);
        }
    }

    private void loadFile(File file) {
        YamlConfiguration fileConfig = YamlConfiguration.loadConfiguration(file);
        for (String level : fileConfig.getKeys(false)) {
            ConfigurationSection section = fileConfig.getConfigurationSection(level);
            if (section == null) {
                continue;
            }
            int key;
            try {
                key = Integer.parseInt(level);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_LOCK_LEVEL_INVALID, level, file.getName()));
                continue;
            }
            if (configs.put(key, GameConfig.from(mergeWithDefault(section))) != null) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_LOCK_LEVEL_DUPLICATE, level, file.getName()));
            }
        }
    }

    /**
     * 等级配置是默认配置（config.yml game 小节）的覆写：先完整拷入默认，再递归合并等级已定义键，
     * 未定义键（含嵌套段的子键，如 sounds 下的 a-move）自然继承默认值。
     */
    private ConfigurationSection mergeWithDefault(ConfigurationSection override) {
        MemoryConfiguration merged = new MemoryConfiguration();
        ConfigurationSection base = defaultConfig != null ? defaultConfig.getSection() : null;
        if (base != null) {
            mergeDeep(merged, base);
        }
        if (override != null) {
            mergeDeep(merged, override);
        }
        return merged;
    }

    /** 递归深合并：source 逐键覆写 target；嵌套段递归合并，未定义的子键保留 target 原值（防止整体替换丢默认）。 */
    private void mergeDeep(ConfigurationSection target, ConfigurationSection source) {
        for (String key : source.getKeys(false)) {
            if (source.get(key) instanceof ConfigurationSection sub) {
                ConfigurationSection targetSub = target.getConfigurationSection(key);
                if (targetSub == null) {
                    targetSub = target.createSection(key);
                }
                mergeDeep(targetSub, sub);
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    /** 获取指定等级的小游戏配置：0 级取配置文件定义的 0 级（未定义用默认）；未配置的正等级就近回退，无可用时用默认。 */
    public GameConfig getGameConfig(int level) {
        if (level <= 0) {
            GameConfig zero = configs.get(0);
            return zero != null ? zero : defaultConfig;
        }
        GameConfig exact = configs.get(level);
        if (exact != null) {
            return exact;
        }
        Integer nearest = nearestLevel(level);
        return nearest == null ? defaultConfig : configs.get(nearest);
    }

    /** 锁等级是否在配置文件中定义；等级 0 恒视为已定义（未定义时用默认配置兜底）。 */
    public boolean isLevelConfigured(int level) {
        return level <= 0 || configs.containsKey(level);
    }

    /** 未配置该等级时，取最接近的更低可用等级；无更低则取更高；都没有返回 null。 */
    private Integer nearestLevel(int level) {
        Integer lower = null;
        Integer higher = null;
        for (Integer configured : configs.keySet()) {
            if (configured < level && (lower == null || configured > lower)) {
                lower = configured;
            } else if (configured > level && (higher == null || configured < higher)) {
                higher = configured;
            }
        }
        return lower != null ? lower : higher;
    }

    /** 返回已配置的全部等级（升序，键越大难度越高）。 */
    public List<Integer> getLevels() {
        List<Integer> levels = new ArrayList<>(configs.keySet());
        Collections.sort(levels);
        return Collections.unmodifiableList(levels);
    }
}
