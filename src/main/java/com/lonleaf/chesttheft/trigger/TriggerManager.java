package com.lonleaf.chesttheft.trigger;

import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 触发器管理器：加载 trigger 文件夹下全部触发器配置，按触发类型分发执行。 */
public class TriggerManager {
    private final JavaPlugin plugin;
    private final File triggerDir;
    private final ActionManager actionManager;
    private final Map<TriggerType, List<Trigger>> triggers = new EnumMap<>(TriggerType.class);
    /** 全局触发器 id 索引（锁触发器引用形式按 id 查找用）。 */
    private final Map<String, Trigger> triggersById = new HashMap<>();
    /** 锁物品自带触发器缓存：YAML 字符串 → 按类型分组的动作。 */
    private final Map<String, Map<TriggerType, List<Action>>> lockTriggerCache = new HashMap<>();

    public TriggerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.triggerDir = new File(plugin.getDataFolder(), "trigger");
        this.actionManager = new ActionManager(plugin);
        saveDefault();
        load();
    }

    /** 保存默认模板 trigger.yml（仅当目录中无任何 yml 时）。 */
    private void saveDefault() {
        File[] files = triggerDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.saveResource("trigger/trigger.yml", false);
        }
    }

    public void load() {
        triggers.clear();
        triggersById.clear();
        for (TriggerType type : TriggerType.values()) {
            triggers.put(type, new ArrayList<>());
        }
        File[] files = triggerDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            loadFile(file);
        }
    }

    private void loadFile(File file) {
        YamlConfiguration fileConfig = YamlConfiguration.loadConfiguration(file);
        for (String id : fileConfig.getKeys(false)) {
            ConfigurationSection section = fileConfig.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            TriggerType type = TriggerType.fromString(section.getString("type"));
            if (type == null) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TRIGGER_INVALID_TYPE,
                        section.getString("type"), id, file.getName()));
                continue;
            }
            List<Action> actions = actionManager.parseActions(section.getConfigurationSection("actions"));
            Trigger trigger = new Trigger(id, type, actions);
            triggers.get(type).add(trigger);
            triggersById.put(id, trigger);
        }
    }

    /** 按 id 查找全局触发器，未找到时返回 null。 */
    private Trigger findTrigger(String id) {
        return triggersById.get(id);
    }

    public void fire(TriggerType type, TriggerContext context) {
        List<Trigger> list = triggers.get(type);
        if (list == null) {
            return;
        }
        for (Trigger trigger : list) {
            trigger.fire(context);
        }
    }

    /** 触发锁物品自带的指定类型触发器；锁未配置该触发器时不处理。 */
    public void fireForLock(String triggerData, TriggerType type, TriggerContext context) {
        if (triggerData == null) {
            return;
        }
        Map<TriggerType, List<Action>> parsed = lockTriggerCache.get(triggerData);
        if (parsed == null) {
            parsed = parseLockTrigger(triggerData);
            lockTriggerCache.put(triggerData, parsed);
        }
        List<Action> actions = parsed.get(type);
        if (actions != null) {
            for (Action action : actions) {
                action.trigger(context);
            }
        }
    }

    /** 解析锁物品的触发器 YAML：支持引用形式（全局触发器 id 列表，按其自身 type 分发）与内嵌定义形式（type + actions），非法项记日志跳过。 */
    private Map<TriggerType, List<Action>> parseLockTrigger(String triggerData) {
        Map<TriggerType, List<Action>> result = new EnumMap<>(TriggerType.class);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(triggerData));
        ConfigurationSection section = config.getConfigurationSection("triggers");
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            Object raw = section.get(id);
            if (raw instanceof List<?> list) {
                // 引用形式：按被引用全局触发器自身的 type 分发
                for (Object o : list) {
                    Trigger ref = triggersById.get(String.valueOf(o));
                    if (ref == null) {
                        plugin.getLogger().warning(Messages.getLog(Messages.LOG_TRIGGER_REF_MISSING, String.valueOf(o), id));
                        continue;
                    }
                    result.computeIfAbsent(ref.type(), k -> new ArrayList<>()).addAll(ref.actions());
                }
                continue;
            }
            ConfigurationSection trigger = section.getConfigurationSection(id);
            if (trigger == null) {
                continue;
            }
            TriggerType type = TriggerType.fromString(trigger.getString("type"));
            if (type == null) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TRIGGER_INVALID_TYPE,
                        trigger.getString("type"), id, "lock-item"));
                continue;
            }
            List<Action> actions = actionManager.parseActions(trigger.getConfigurationSection("actions"));
            if (!actions.isEmpty()) {
                result.computeIfAbsent(type, k -> new ArrayList<>()).addAll(actions);
            }
        }
        return result;
    }
}
