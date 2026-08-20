package com.lonleaf.chesttheft.lootchest;

import com.lonleaf.chesttheft.config.ColorParser;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.ItemManager;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 战利品箱配置档案：标题、展示材质、掉落、撬锁等级、开箱特效、物品列表与掉落映射（drops）。
 */
public class LootChestProfile {
    private final String id;
    private final String title;
    private final Material displayMaterial;
    private final boolean includeDrops;
    private final double chance;
    private final int level;
    private final List<LootChestItem> items;
    private final OpenEffectionConfig openEffection;
    /** 掉落实体映射表：每个条目为 "实体标识符 概率"。 */
    private final List<DropEntry> drops;

    private LootChestProfile(String id, String title, Material displayMaterial,
                             boolean includeDrops, double chance, int level, List<LootChestItem> items,
                             OpenEffectionConfig openEffection, List<DropEntry> drops) {
        this.id = id;
        this.title = title;
        this.displayMaterial = displayMaterial;
        this.includeDrops = includeDrops;
        this.chance = chance;
        this.level = level;
        this.items = items;
        this.openEffection = openEffection;
        this.drops = drops;
    }

    /** 从配置段解析档案；非法展示材质回退 CHEST 并告警，非法物品跳过。 */
    public static LootChestProfile from(String id, ConfigurationSection section, Logger logger) {
        String title = section.getString("title");
        String materialName = section.getString("display-material", "CHEST");
        Material displayMaterial = Material.CHEST;
        try {
            displayMaterial = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, Messages.getLog(Messages.LOG_LOOT_CHEST_INVALID_MATERIAL, id, materialName));
        }
        boolean includeDrops = section.getBoolean("include-drops", true);
        double chance = Math.max(0.0, Math.min(1.0, section.getDouble("chance", 1.0)));
        int level = section.getInt("level", -1);
        List<LootChestItem> items = new ArrayList<>();
        for (Map<?, ?> map : section.getMapList("items")) {
            LootChestItem item = LootChestItem.from(map, id, logger);
            if (item != null) {
                items.add(item);
            }
        }
        OpenEffectionConfig openEffection = OpenEffectionConfig.from(
                section.getConfigurationSection("open-effects"), logger, id);
        List<DropEntry> drops = parseDrops(section.getStringList("drops"), logger, id);
        return new LootChestProfile(id, title, displayMaterial, includeDrops, chance, level, items, openEffection, drops);
    }

    /** 解析 drops 配置行列表，格式 "实体标识符 概率"。 */
    private static List<DropEntry> parseDrops(List<String> raw, Logger logger, String profileId) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<DropEntry> result = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line == null || line.isBlank()) continue;
            String trimmed = line.trim();
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace < 0) {
                logger.warning(Messages.getLog(Messages.LOG_LOOT_DROPS_FORMAT_INVALID, profileId, trimmed));
                continue;
            }
            String identifier = trimmed.substring(0, lastSpace).trim();
            String probStr = trimmed.substring(lastSpace + 1).trim();
            double probability;
            try {
                probability = Double.parseDouble(probStr);
            } catch (NumberFormatException e) {
                logger.warning(Messages.getLog(Messages.LOG_LOOT_DROPS_PROBABILITY_INVALID, profileId, trimmed));
                continue;
            }
            result.add(new DropEntry(identifier, Math.max(0.0, Math.min(1.0, probability))));
        }
        return result;
    }

    /** 匹配某实体标识符是否在此档案的掉落映射中。 */
    public boolean hasDrop(String entityIdentifier) {
        for (DropEntry entry : drops) {
            if (entry.entityIdentifier.equalsIgnoreCase(entityIdentifier)) {
                return true;
            }
        }
        return false;
    }

    /** 获取某实体在此档案中的掉落概率；未匹配返回 0。 */
    public double getDropProbability(String entityIdentifier) {
        for (DropEntry entry : drops) {
            if (entry.entityIdentifier.equalsIgnoreCase(entityIdentifier)) {
                return entry.probability;
            }
        }
        return 0.0;
    }

    /** 构建档案中的全部额外物品；items 文件夹引用缺失的物品被跳过。 */
    public List<ItemStack> buildItems(ItemManager itemManager) {
        List<ItemStack> result = new ArrayList<>();
        for (LootChestItem item : items) {
            if (Math.random() > item.getChance()) continue;
            ItemStack built = item.build(itemManager);
            if (built != null) {
                result.add(built);
            }
        }
        return result;
    }

    public String getId() {
        return id;
    }

    /** 箱子界面标题（含颜色码，未配置时为 null，由创建方使用默认标题）。 */
    public String getTitle() {
        return title;
    }

    /** 展示实体显示的方块材质（display 模式）。 */
    public Material getDisplayMaterial() {
        return displayMaterial;
    }

    /** 是否将生物自然掉落物装入箱子；false 时箱子仅含配置物品。 */
    public boolean isIncludeDrops() {
        return includeDrops;
    }

    /** 生物死亡时生成箱子的概率（0~1）。 */
    public double getChance() {
        return chance;
    }

    /** 撬锁等级：-1 无需撬锁直接打开；>= 0 需撬锁（对应 gamelevel/ 中的等级配置）。 */
    public int getLevel() {
        return level;
    }

    public List<LootChestItem> getItems() {
        return items;
    }

    /** 开箱特效配置；未配置（null）时开箱仅播放默认音效。 */
    public OpenEffectionConfig getOpenEffection() {
        return openEffection;
    }

    /** 该档案的掉落映射（drops）；可能为空。 */
    public List<DropEntry> getDrops() {
        return drops;
    }

    /** 掉落映射条目：关联实体标识符与对应概率。 */
    public static class DropEntry {
        private final String entityIdentifier;
        private final double probability;

        private DropEntry(String entityIdentifier, double probability) {
            this.entityIdentifier = entityIdentifier;
            this.probability = probability;
        }

        /** 实体标识符，如 "zombie"、"mythicmobs:example_mob"。 */
        public String getEntityIdentifier() {
            return entityIdentifier;
        }

        /** 掉落概率（0~1）。 */
        public double getProbability() {
            return probability;
        }
    }

    /**
     * 开箱特效配置：发光、粒子与音效三项独立可选，均使用单行字符串配置。
     */
    public static class OpenEffectionConfig {
        private final GlowConfig glow;
        private final ParticlesConfig particles;
        private final SoundConfig sound;

        private OpenEffectionConfig(GlowConfig glow, ParticlesConfig particles, SoundConfig sound) {
            this.glow = glow;
            this.particles = particles;
            this.sound = sound;
        }

        /** 从 open-effects 配置段解析；段可缺省，缺省时仅启用默认开箱音效。 */
        static OpenEffectionConfig from(ConfigurationSection section, Logger logger, String profileId) {
            GlowConfig glow = null;
            ParticlesConfig particles = null;
            SoundConfig sound = SoundConfig.DEFAULT;
            if (section != null) {
                glow = GlowConfig.from(section.getString("glow"), logger, profileId);
                particles = ParticlesConfig.from(section.getString("particles"), logger, profileId);
                SoundConfig configuredSound = SoundConfig.from(section.getString("sound"), logger, profileId);
                if (configuredSound != null) {
                    sound = configuredSound;
                }
            }
            return new OpenEffectionConfig(glow, particles, sound);
        }

        /** 通用枚举解析：未配置返回 null，非法值告警后返回 null。 */
        private static <T extends Enum<T>> T parseEnum(String value, Class<T> type, Logger logger,
                                                       String field, String profileId) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning(Messages.getLog(Messages.LOG_LOOT_FIELD_VALUE_INVALID, profileId, field, value));
                return null;
            }
        }

        /**
         * 音效解析：1.21.3+ Sound 由枚举改为接口，优先按资源 key（Registry.SOUNDS）解析，
         * 兼容旧枚举名与直接 key，旧版服务端回退 Enum.valueOf。
         */
        private static Sound parseSound(String value, Logger logger, String profileId) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String trimmed = value.trim();
            // 新版服务端：优先 Registry 按资源 key 解析
            try {
                String keyStr = trimmed.contains(":") ? trimmed : trimmed.toLowerCase(Locale.ROOT).replace('_', '.');
                NamespacedKey key = NamespacedKey.fromString(keyStr);
                if (key != null) {
                    Sound sound = Registry.SOUNDS.get(key);
                    if (sound != null) {
                        return sound;
                    }
                }
            } catch (Exception ignored) {
                // 继续走枚举回退
            }
            // 旧版服务端：Sound 仍为枚举时按枚举名解析
            try {
                return Enum.valueOf(Sound.class, trimmed.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning(Messages.getLog(Messages.LOG_LOOT_SOUND_TYPE_INVALID, profileId, value));
                return null;
            }
        }

        /** 通用数字解析：非法值告警后回退默认。 */
        private static double parseNumber(String value, double def, Logger logger, String field, String profileId) {
            if (value == null || value.isBlank()) {
                return def;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                logger.warning(Messages.getLog(Messages.LOG_LOOT_FIELD_DEFAULT_USED, profileId, field, value, def));
                return def;
            }
        }

        /** 发光配置；null 表示该箱子不发光。 */
        public GlowConfig getGlow() {
            return glow;
        }

        /** 粒子配置；null 表示无粒子。 */
        public ParticlesConfig getParticles() {
            return particles;
        }

        /** 音效配置；恒非 null（缺省 BLOCK_CHEST_OPEN）。 */
        public SoundConfig getSound() {
            return sound;
        }

        /** 发光特效：单行 "颜色 [时长tick]"，如 "GOLD 6"；未配置不发光。 */
        public static class GlowConfig {
            private final Color color;
            private final long durationTicks;

            private GlowConfig(Color color, long durationTicks) {
                this.color = color;
                this.durationTicks = durationTicks;
            }

            static GlowConfig from(String value, Logger logger, String profileId) {
                if (value == null || value.isBlank()) {
                    return null;
                }
                String[] tokens = value.trim().split("\\s+");
                Color color = ColorParser.parse(tokens[0]);
                if (color == null) {
                    logger.warning(Messages.getLog(Messages.LOG_LOOT_GLOW_COLOR_INVALID, profileId, tokens[0]));
                    return null;
                }
                long duration = tokens.length > 1
                        ? (long) Math.max(1, parseNumber(tokens[1], 6, logger, "open-effects glow duration", profileId))
                        : 6L;
                return new GlowConfig(color, duration);
            }

            public Color getColor() {
                return color;
            }

            public long getDurationTicks() {
                return durationTicks;
            }
        }

        /** 扬起尘土粒子特效：单行 "类型 [数量]"，如 "CLOUD 12"；未配置无粒子。 */
        public static class ParticlesConfig {
            private final Particle type;
            private final int count;

            private ParticlesConfig(Particle type, int count) {
                this.type = type;
                this.count = count;
            }

            static ParticlesConfig from(String value, Logger logger, String profileId) {
                if (value == null || value.isBlank()) {
                    return null;
                }
                String[] tokens = value.trim().split("\\s+");
                Particle type = parseEnum(tokens[0], Particle.class, logger,
                        "open-effects particles type", profileId);
                if (type == null) {
                    return null;
                }
                int count = tokens.length > 1
                        ? (int) Math.max(1, parseNumber(tokens[1], 12, logger, "open-effects particles count", profileId))
                        : 12;
                return new ParticlesConfig(type, count);
            }

            public Particle getType() {
                return type;
            }

            public int getCount() {
                return count;
            }
        }

        /** 开箱音效：单行 "类型 [音量] [音调]"，如 "BLOCK_CHEST_OPEN 1.0 1.0"；未配置默认 BLOCK_CHEST_OPEN 1.0 1.0。 */
        public static class SoundConfig {
            private static final SoundConfig DEFAULT = new SoundConfig(Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);

            private final Sound type;
            private final float volume;
            private final float pitch;

            private SoundConfig(Sound type, float volume, float pitch) {
                this.type = type;
                this.volume = volume;
                this.pitch = pitch;
            }

            static SoundConfig from(String value, Logger logger, String profileId) {
                if (value == null || value.isBlank()) {
                    return null;
                }
                String[] tokens = value.trim().split("\\s+");
                Sound type = parseSound(tokens[0], logger, profileId);
                if (type == null) {
                    type = Sound.BLOCK_CHEST_OPEN;
                }
                float volume = (float) parseNumber(tokens.length > 1 ? tokens[1] : null, 1.0, logger,
                        "open-effects sound volume", profileId);
                float pitch = (float) parseNumber(tokens.length > 2 ? tokens[2] : null, 1.0, logger,
                        "open-effects sound pitch", profileId);
                return new SoundConfig(type, volume, pitch);
            }

            public Sound getType() {
                return type;
            }

            public float getVolume() {
                return volume;
            }

            public float getPitch() {
                return pitch;
            }
        }
    }
}
