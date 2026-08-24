package com.lonleaf.chesttheft.trigger;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemDefinition;
import com.lonleaf.chesttheft.item.ItemType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
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
    /** 触发器 id 索引：全局触发器 + 物品内嵌定义转换的临时触发器（def:<物品ID>:<触发器键>），锁物品按 id 解析用。 */
    private final Map<String, Trigger> triggersById = new HashMap<>();

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

    /**
     * 按 id 列表触发锁物品的触发器：只执行与事件类型匹配的触发器动作；
     * 未注册的 id（如物品定义已被删除）记日志跳过，不中断其余触发器。
     */
    public void fireForLock(List<String> triggerIds, TriggerType type, TriggerContext context) {
        if (triggerIds == null || triggerIds.isEmpty()) {
            return;
        }
        for (String id : triggerIds) {
            Trigger trigger = triggersById.get(id);
            if (trigger == null) {
                plugin.getLogger().warning(Messages.getLog(Messages.LOG_TRIGGER_REF_MISSING, id, "lock-item"));
                continue;
            }
            if (trigger.type() == type) {
                for (Action action : trigger.actions()) {
                    action.trigger(context);
                }
            }
        }
    }

    /**
     * 将物品定义中的内嵌触发器注册为临时 id（def:<物品ID>:<触发器键>），动作只进内存注册表。
     * 引用形式（值为 id 列表）无内嵌动作，跳过；须在 load() 清空注册表后调用（onEnable / reload）。
     */
    public void syncItemTriggers(Collection<ItemDefinition> lockDefs) {
        if (lockDefs == null) {
            return;
        }
        for (ItemDefinition def : lockDefs) {
            if (def.getType() != ItemType.LOCK || def.getTriggers() == null) {
                continue;
            }
            for (String key : def.getTriggers().getKeys(false)) {
                if (def.getTriggers().get(key) instanceof List) {
                    continue; // 引用形式：触发时按 id 查全局触发器
                }
                ConfigurationSection t = def.getTriggers().getConfigurationSection(key);
                if (t == null) {
                    continue;
                }
                TriggerType type = TriggerType.fromString(t.getString("type"));
                if (type == null) {
                    plugin.getLogger().warning(Messages.getLog(Messages.LOG_TRIGGER_INVALID_TYPE,
                            t.getString("type"), key, def.getId()));
                    continue;
                }
                String tempId = "def:" + def.getId() + ":" + key;
                List<Action> actions = actionManager.parseActions(t.getConfigurationSection("actions"));
                triggersById.put(tempId, new Trigger(tempId, type, actions));
            }
        }
    }

    /**
     * 解析锁物品 PDC 中的触发器 id 列表。兼容新格式（triggers 为扁平 id 列表）与旧引用形式
     * （triggers 为键值结构、值为 id 列表）；内嵌定义段（值为配置段）一律忽略——不信任物品 PDC 中的动作。
     */
    public List<String> parseTriggerIds(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(data));
            Object raw = config.get("triggers");
            if (raw instanceof List<?> list) {
                // 新格式：扁平 id 列表
                for (Object o : list) {
                    ids.add(String.valueOf(o));
                }
            } else if (raw instanceof ConfigurationSection section) {
                // 旧引用形式：值为 id 列表
                for (String key : section.getKeys(false)) {
                    if (section.get(key) instanceof List<?> list) {
                        for (Object o : list) {
                            ids.add(String.valueOf(o));
                        }
                    }
                    // 内嵌定义段（ConfigurationSection）忽略
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_TRIGGER_PARSE_FAIL, e.getMessage()));
        }
        return ids.isEmpty() ? null : ids;
    }
}
