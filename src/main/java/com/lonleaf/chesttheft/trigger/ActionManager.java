package com.lonleaf.chesttheft.trigger;

import com.lonleaf.chesttheft.config.Messages;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 动作管理器：注册内置动作（参照 CustomFishing 动作系统），并把配置段解析为可执行动作。 */
public class ActionManager {
    private final JavaPlugin plugin;
    private final Map<String, ActionFactory> factories = new HashMap<>();

    /** 动作工厂：由 value 与触发概率构造动作实例。 */
    @FunctionalInterface
    public interface ActionFactory {
        Action create(Object value, double chance);
    }

    public ActionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        registerBuiltins();
    }

    /** 解析单个动作（type / value / chance），未知类型记日志并返回 null。 */
    public Action parseAction(ConfigurationSection section) {
        String type = section.getString("type");
        ActionFactory factory = factories.get(type);
        if (factory == null) {
            plugin.getLogger().warning(Messages.getLog(Messages.LOG_ACTION_INVALID_TYPE, type));
            return null;
        }
        double chance = section.getDouble("chance", 1.0);
        return factory.create(section.get("value"), chance);
    }

    /** 解析一组动作（以动作 id 为键的配置段），非法动作自动跳过。 */
    public List<Action> parseActions(ConfigurationSection section) {
        List<Action> actions = new ArrayList<>();
        if (section == null) {
            return actions;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection inner = section.getConfigurationSection(key);
            if (inner == null) {
                continue;
            }
            Action action = parseAction(inner);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    @SuppressWarnings("deprecation")
    private void registerBuiltins() {
        // 消息类
        register("message", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            for (String line : toList(value)) {
                ctx.player().sendMessage(color(ctx.render(line)));
            }
        });
        register("random-message", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            List<String> list = toList(value);
            if (!list.isEmpty()) {
                ctx.player().sendMessage(color(ctx.render(list.get(ThreadLocalRandom.current().nextInt(list.size())))));
            }
        });
        register("broadcast", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                for (String line : toList(value)) {
                    online.sendMessage(color(ctx.render(line)));
                }
            }
        });
        // 命令类
        register("command", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            for (String command : toList(value)) {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), ctx.render(command));
            }
        });
        register("player-command", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            for (String command : toList(value)) {
                ctx.player().performCommand(ctx.render(command));
            }
        });
        register("random-command", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            List<String> list = toList(value);
            if (!list.isEmpty()) {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                        ctx.render(list.get(ThreadLocalRandom.current().nextInt(list.size()))));
            }
        });
        // 界面类
        register("close-inv", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            ctx.player().closeInventory();
        });
        register("actionbar", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            ctx.player().spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(color(ctx.render(String.valueOf(value)))));
        });
        register("random-actionbar", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            List<String> list = toList(value);
            if (!list.isEmpty()) {
                ctx.player().spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(color(ctx.render(list.get(ThreadLocalRandom.current().nextInt(list.size()))))));
            }
        });
        register("title", (value, chance) -> {
            ConfigurationSection v = asSection(value);
            String title = v.getString("title", "");
            String subtitle = v.getString("subtitle", "");
            int fadeIn = v.getInt("fade-in", 20);
            int stay = v.getInt("stay", 30);
            int fadeOut = v.getInt("fade-out", 10);
            return ctx -> {
                if (Math.random() > chance) return;
                ctx.player().sendTitle(color(ctx.render(title)), color(ctx.render(subtitle)), fadeIn, stay, fadeOut);
            };
        });
        register("random-title", (value, chance) -> {
            ConfigurationSection v = asSection(value);
            List<String> titles = v.getStringList("titles");
            List<String> subtitles = v.getStringList("subtitles");
            if (titles.isEmpty()) titles = List.of("");
            if (subtitles.isEmpty()) subtitles = List.of("");
            int fadeIn = v.getInt("fade-in", 20);
            int stay = v.getInt("stay", 30);
            int fadeOut = v.getInt("fade-out", 10);
            final List<String> titlePool = titles;
            final List<String> subtitlePool = subtitles;
            return ctx -> {
                if (Math.random() > chance) return;
                String title = titlePool.get(ThreadLocalRandom.current().nextInt(titlePool.size()));
                String subtitle = subtitlePool.get(ThreadLocalRandom.current().nextInt(subtitlePool.size()));
                ctx.player().sendTitle(color(ctx.render(title)), color(ctx.render(subtitle)), fadeIn, stay, fadeOut);
            };
        });
        register("sound", (value, chance) -> {
            ConfigurationSection v = asSection(value);
            String key = v.getString("key");
            SoundCategory category = parseCategory(v.getString("source"));
            float volume = (float) v.getDouble("volume", 1.0);
            float pitch = (float) v.getDouble("pitch", 1.0);
            return ctx -> {
                if (Math.random() > chance || key == null) return;
                ctx.player().playSound(ctx.player().getLocation(), key, category, volume, pitch);
            };
        });
        // 属性类
        register("exp", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            ctx.player().giveExp((int) Math.round(number(value)));
        });
        register("level", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            Player p = ctx.player();
            p.setLevel(Math.max(0, p.getLevel() + (int) Math.round(number(value))));
        });
        register("food", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            Player p = ctx.player();
            p.setFoodLevel(Math.max(0, Math.min(20, p.getFoodLevel() + (int) Math.round(number(value)))));
        });
        register("saturation", (value, chance) -> ctx -> {
            if (Math.random() > chance) return;
            Player p = ctx.player();
            p.setSaturation(Math.max(0, p.getSaturation() + (float) number(value)));
        });
        register("potion-effect", (value, chance) -> {
            ConfigurationSection v = asSection(value);
            PotionEffectType type = PotionEffectType.getByName(v.getString("type", "").toUpperCase(Locale.ENGLISH));
            int duration = v.getInt("duration", 20);
            int amplifier = v.getInt("amplifier", 0);
            return ctx -> {
                if (Math.random() > chance || type == null) return;
                ctx.player().addPotionEffect(new PotionEffect(type, duration, amplifier));
            };
        });
        // 组合类
        register("chain", (value, chance) -> {
            List<Action> actions = parseActions(asSection(value));
            return ctx -> {
                if (Math.random() > chance) return;
                for (Action action : actions) {
                    action.trigger(ctx);
                }
            };
        });
        register("delay", (value, chance) -> {
            ConfigurationSection v = asSection(value);
            int delay = v.getInt("delay", 1);
            List<Action> actions = parseActions(v.getConfigurationSection("actions"));
            return ctx -> {
                if (Math.random() > chance) return;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    for (Action action : actions) {
                        action.trigger(ctx);
                    }
                }, delay);
            };
        });
        // 物品类
        register("give-item", (value, chance) -> {
            ConfigurationSection v = asSection(value);
            Material material = Material.matchMaterial(v.getString("item", ""));
            int amount = Math.max(1, v.getInt("amount", 1));
            boolean toInventory = v.getBoolean("to-inventory", true);
            return ctx -> {
                if (Math.random() > chance || material == null) return;
                ItemStack item = new ItemStack(material, amount);
                if (toInventory) {
                    for (ItemStack drop : ctx.player().getInventory().addItem(item).values()) {
                        ctx.player().getWorld().dropItemNaturally(ctx.player().getLocation(), drop);
                    }
                } else {
                    ctx.player().getWorld().dropItemNaturally(ctx.player().getLocation(), item);
                }
            };
        });
    }

    private void register(String type, ActionFactory factory) {
        factories.put(type, factory);
    }

    private static List<String> toList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static ConfigurationSection asSection(Object value) {
        return value instanceof ConfigurationSection section ? section : new YamlConfiguration();
    }

    private static double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SoundCategory parseCategory(String source) {
        if (source == null) {
            return SoundCategory.PLAYERS;
        }
        try {
            return SoundCategory.valueOf(source.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return SoundCategory.PLAYERS;
        }
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
