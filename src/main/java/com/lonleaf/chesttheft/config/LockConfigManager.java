package com.lonleaf.chesttheft.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 不同等级锁的小游戏配置（lock 文件夹下全部 yml）：数字等级 → GameConfig；等级 0 定义时覆盖默认配置。 */
public class LockConfigManager {
    private final JavaPlugin plugin;
    private final File lockDir;
    private final Map<Integer, GameConfig> configs = new HashMap<>();
    /** 默认配置（config.yml 的 game 小节），配置文件未定义 0 级时的兜底。 */
    private volatile GameConfig defaultConfig;

    public LockConfigManager(JavaPlugin plugin, GameConfig defaultConfig) {
        this.plugin = plugin;
        this.lockDir = new File(plugin.getDataFolder(), "lock");
        this.defaultConfig = defaultConfig;
        saveDefault();
        load();
    }

    /** reload 时更新默认配置（等级 0 未在配置文件中定义时的兜底）。 */
    public void updateDefaultConfig(GameConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    /** 保存默认模板 lock.yml（仅当 lock 目录下没有任何 yml 时创建）。 */
    private void saveDefault() {
        File[] files = lockDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.saveResource("lock/lock.yml", false);
        }
    }

    public void load() {
        configs.clear();
        File[] files = lockDir.listFiles((dir, name) -> name.endsWith(".yml"));
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
            if (configs.put(key, GameConfig.from(section)) != null) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_LOCK_LEVEL_DUPLICATE, level, file.getName()));
            }
        }
    }

    /** 获取指定等级的小游戏配置：等级 0 取配置文件中定义的 0 级配置（未定义时用默认配置）；未配置的正等级取最接近的更低可用等级，无更低则取更高，都没有时返回默认配置。 */
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

    /** 锁等级是否在配置文件中定义（精确匹配）；等级 0 恒视为已定义（默认配置存在，定义 0 级时覆盖）。 */
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
