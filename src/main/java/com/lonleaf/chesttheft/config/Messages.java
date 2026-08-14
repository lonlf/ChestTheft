package com.lonleaf.chesttheft.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Messages {

    private static final Logger LOGGER = Logger.getLogger(Messages.class.getName());

    /** 当前语言代码 */
    private static volatile String currentLang = "en_gb";

    /** 语言文件目录 */
    private static Path langDir;

    /** 默认资源路径前缀（打包在 JAR 中的内置语言文件） */
    private static final String RESOURCE_PREFIX = "/lang/";

    /** 内置语言文件清单（新增语言需在此登记，同时作为语言族回退的候选） */
    private static final String[] BUILTIN_LANGS = {"zh_cn", "en_gb"};

    /** 消息存储（ConcurrentHashMap 保证 reload 时并发读取线程安全） */
    private static final Map<String, String> messages = new ConcurrentHashMap<>();

    /** 各消息显示方式（来自 config.yml 的 message-format 小节）：消息键 → message/actionbar/title/subtitle。 */
    private static final Map<String, String> formats = new HashMap<>();

    // ==================== 玩家消息 ====================

    public static volatile String SUCCESS;
    public static volatile String FAIL;
    public static volatile String START_PICKING;
    public static volatile String CANCEL_PICKING;
    public static volatile String RULE;
    public static volatile String LOCKED_IT;
    public static volatile String CHEST_LOCKED;
    public static volatile String GAME_TIMEOUT;
    public static volatile String COOLDOWN;
    public static volatile String NO_PERMISSION;
    public static volatile String PLAYER_NOT_FOUND;
    public static volatile String INVALID_ID;
    public static volatile String INVALID_AMOUNT;
    public static volatile String GIVEN_ITEM;
    public static volatile String SETITEM_SUCCESS;
    public static volatile String SETITEM_NO_ITEM;
    public static volatile String CHECK_PDC;
    public static volatile String CHECK_KEY_INFO;
    public static volatile String CHECK_NOT_SPECIAL;
    public static volatile String NONE;
    public static volatile String UNLOCK_SUCCESS;
    public static volatile String UNLOCK_CONFIRM;
    public static volatile String PAIR_SUCCESS;
    public static volatile String PAIR_NOT_LOCKER;
    public static volatile String KEY_NOT_MATCHED;
    public static volatile String KEY_CHANGED;
    public static volatile String PLAYER_ONLY;
    public static volatile String RELOADED;
    public static volatile String USAGE;

    // ============ 物品 Lore 展示信息（协议包注入，固定文本样式） ============

    public static volatile String LORE_TYPE;
    public static volatile String LORE_PAIRED;
    public static volatile String LORE_NOT_PAIRED;

    // ============ 物品类型名称（key / lock / picker 的本地化显示） ============

    public static volatile String TYPE_KEY;
    public static volatile String TYPE_LOCK;
    public static volatile String TYPE_PICKER;

    // ============ 玩家消息显示方式（message / actionbar / title / subtitle） ============

    public static volatile String SUCCESS_FORMAT;
    public static volatile String FAIL_FORMAT;
    public static volatile String START_PICKING_FORMAT;
    public static volatile String CANCEL_PICKING_FORMAT;
    public static volatile String RULE_FORMAT;
    public static volatile String LOCKED_IT_FORMAT;
    public static volatile String CHEST_LOCKED_FORMAT;
    public static volatile String GAME_TIMEOUT_FORMAT;
    public static volatile String COOLDOWN_FORMAT;
    public static volatile String NO_PERMISSION_FORMAT;
    public static volatile String PLAYER_NOT_FOUND_FORMAT;
    public static volatile String INVALID_ID_FORMAT;
    public static volatile String INVALID_AMOUNT_FORMAT;
    public static volatile String GIVEN_ITEM_FORMAT;
    public static volatile String SETITEM_SUCCESS_FORMAT;
    public static volatile String SETITEM_NO_ITEM_FORMAT;
    public static volatile String CHECK_PDC_FORMAT;
    public static volatile String CHECK_KEY_INFO_FORMAT;
    public static volatile String CHECK_NOT_SPECIAL_FORMAT;
    public static volatile String UNLOCK_SUCCESS_FORMAT;
    public static volatile String UNLOCK_CONFIRM_FORMAT;
    public static volatile String PAIR_SUCCESS_FORMAT;
    public static volatile String PAIR_NOT_LOCKER_FORMAT;
    public static volatile String KEY_NOT_MATCHED_FORMAT;
    public static volatile String KEY_CHANGED_FORMAT;
    public static volatile String PLAYER_ONLY_FORMAT;
    public static volatile String RELOADED_FORMAT;
    public static volatile String USAGE_FORMAT;

    // ==================== 服务器日志 ====================

    public static volatile String LOG_ENABLED;
    public static volatile String LOG_LANG_DETECTED;
    public static volatile String LOG_LANG_FILE_MISSING;
    public static volatile String LOG_ITEM_DUPLICATE;
    public static volatile String LOG_ITEM_INVALID_TYPE;
    public static volatile String LOG_ITEM_INVALID_MATERIAL;
    public static volatile String LOG_DB_INIT;
    public static volatile String LOG_DB_CONNECT_FAIL;
    public static volatile String LOG_DB_CREATE_TABLE_FAIL;
    public static volatile String LOG_DB_QUERY_STATE_FAIL;
    public static volatile String LOG_DB_LOCK_DUP;
    public static volatile String LOG_DB_LOCKER_FAIL;
    public static volatile String LOG_DB_UNLOCK_FAIL;
    public static volatile String LOG_DB_ITEM_FAIL;
    public static volatile String LOG_DB_CLOSE_FAIL;
    public static volatile String LOG_PACKET_LORE_FAIL;
    public static volatile String LOG_PACKET_DEBUG;
    public static volatile String LOG_CONFIG_LOAD_FAIL;
    public static volatile String LOG_CONFIG_UPGRADE_FAILED;
    public static volatile String LOG_CONFIG_UPGRADE_DONE;
    public static volatile String LOG_LANG_WRITE_FAIL;
    public static volatile String LOG_CONFIG_LOADED_DEBUG;

    // ==================== 初始化 ====================

    /**
     * 初始化消息系统：创建 lang 目录、提取内置语言文件并加载指定语言。
     *
     * @param dataDirectory 插件数据目录
     * @param lang          语言代码（如 "zh_cn"、"en_gb"）
     */
    public static void init(Path dataDirectory, String lang) {
        langDir = dataDirectory.resolve("lang");
        currentLang = (lang != null && !lang.isBlank()) ? lang : "en_gb";
        try {
            Files.createDirectories(langDir);
            extractBuiltinLanguages();
            loadMessages(currentLang);
            LOGGER.info("Messages system initialized (lang=" + currentLang + ")");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize messages system", e);
        }
    }

    /**
     * 重新加载指定语言的消息（语言代码为空时沿用当前语言）。
     */
    public static void reload(String lang) {
        if (lang != null && !lang.isBlank()) {
            currentLang = lang;
        }
        loadMessages(currentLang);
        LOGGER.info("Messages reloaded (lang=" + currentLang + ")");
    }

    /**
     * 获取玩家消息：& 颜色码转 § 并替换 {0}, {1} 等占位符。
     *
     * @param template 消息模板（通常传静态字段，如 Messages.COOLDOWN）
     * @param args     占位符参数
     */
    public static String get(String template, Object... args) {
        if (template == null) {
            return "";
        }
        return format(ChatColor.translateAlternateColorCodes('&', template), args);
    }

    /**
     * 获取日志消息：仅替换 {0} 等占位符，不处理颜色码。
     */
    public static String getLog(String template, Object... args) {
        if (template == null) {
            return "";
        }
        return format(template, args);
    }

    /**
     * 按配置的显示方式发送玩家消息：
     * message（聊天）/ actionbar（动作栏）/ title（标题），
     * 非玩家或未知格式回退为聊天消息。
     *
     * @param sender 接收者（Player 支持全部显示方式，其余仅聊天消息）
     * @param text   消息模板（通常传静态字段，如 Messages.CHEST_LOCKED）
     * @param format 显示方式（通常传对应静态格式字段，如 Messages.CHEST_LOCKED_FORMAT）
     * @param args   占位符参数
     */
    public static void send(CommandSender sender, String text, String format, Object... args) {
        String msg = get(text, args);
        if (!(sender instanceof Player player) || format == null || format.equalsIgnoreCase("message")) {
            sender.sendMessage(msg);
            return;
        }
        switch (format.toLowerCase(Locale.ROOT)) {
            case "actionbar" -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
            // subtitle 已合并到 title：配置为 subtitle 时同样以标题显示
            case "title", "subtitle" -> player.sendTitle(msg, "", 6, 40, 6);
            default -> sender.sendMessage(msg);
        }
    }

    /**
     * 应用 config.yml 中 message-format 小节的显示方式配置（消息键 → message/actionbar/title）。
     * 应在语言加载完成后调用；reload 时需再次调用。
     */
    public static void applyFormats(Map<String, String> formatMap) {
        formats.clear();
        if (formatMap != null) {
            formats.putAll(formatMap);
        }
        loadFormats();
    }

    /** 按配置的显示方式刷新各消息格式字段，缺失的键使用默认值。 */
    private static void loadFormats() {
        SUCCESS_FORMAT = formats.getOrDefault("pickingSuccess", "message");
        FAIL_FORMAT = formats.getOrDefault("pickingFail", "message");
        START_PICKING_FORMAT = formats.getOrDefault("pickingStart", "message");
        CANCEL_PICKING_FORMAT = formats.getOrDefault("pickingCancel", "message");
        RULE_FORMAT = formats.getOrDefault("pickingRule", "actionbar");
        LOCKED_IT_FORMAT = formats.getOrDefault("lockSuccess", "title");
        CHEST_LOCKED_FORMAT = formats.getOrDefault("chestLocked", "message");
        GAME_TIMEOUT_FORMAT = formats.getOrDefault("pickingTimeout", "message");
        COOLDOWN_FORMAT = formats.getOrDefault("pickingCooldown", "message");
        NO_PERMISSION_FORMAT = formats.getOrDefault("noPermission", "message");
        PLAYER_NOT_FOUND_FORMAT = formats.getOrDefault("playerNotFound", "message");
        INVALID_ID_FORMAT = formats.getOrDefault("invalidId", "message");
        INVALID_AMOUNT_FORMAT = formats.getOrDefault("invalidAmount", "message");
        GIVEN_ITEM_FORMAT = formats.getOrDefault("giveSuccess", "message");
        SETITEM_SUCCESS_FORMAT = formats.getOrDefault("setItemSuccess", "message");
        SETITEM_NO_ITEM_FORMAT = formats.getOrDefault("setItemNoItem", "message");
        // check 为管理员诊断命令，固定使用聊天消息，不参与显示方式配置
        CHECK_PDC_FORMAT = "message";
        CHECK_KEY_INFO_FORMAT = "message";
        CHECK_NOT_SPECIAL_FORMAT = formats.getOrDefault("checkNotSpecial", "message");
        UNLOCK_SUCCESS_FORMAT = formats.getOrDefault("unlockSuccess", "message");
        UNLOCK_CONFIRM_FORMAT = formats.getOrDefault("unlockConfirm", "message");
        PAIR_SUCCESS_FORMAT = formats.getOrDefault("pairSuccess", "message");
        PAIR_NOT_LOCKER_FORMAT = formats.getOrDefault("pairNotLocker", "message");
        KEY_NOT_MATCHED_FORMAT = formats.getOrDefault("keyNotMatched", "message");
        KEY_CHANGED_FORMAT = formats.getOrDefault("keyChanged", "message");
        PLAYER_ONLY_FORMAT = formats.getOrDefault("playerOnly", "message");
        // USAGE 与 RELOADED 仅管理员可见，固定使用聊天消息，不参与显示方式配置
        USAGE_FORMAT = "message";
        RELOADED_FORMAT = "message";
    }

    /** 获取当前语言代码。 */
    public static String getCurrentLang() {
        return currentLang;
    }

    /**
     * 判断语言代码是否受支持：外部 lang/ 目录或 JAR 内置资源中存在对应语言文件。
     */
    public static boolean isSupportedLanguage(String lang) {
        if (lang == null || lang.isBlank()) {
            return false;
        }
        if (langDir != null && Files.exists(langDir.resolve(lang + ".yml"))) {
            return true;
        }
        try (InputStream in = Messages.class.getResourceAsStream(RESOURCE_PREFIX + lang + ".yml")) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 按系统 Locale 选择语言（i18n 父子匹配链）：
     * 完整 Locale（zh_CN→zh_cn, en_GB→en_gb）→ 语言主码（zh/en）→ 语言族回退（zh_tw→zh_cn, en_us→en_gb）→ 默认 en_gb。
     */
    public static String detectSystemLanguage() {
        Locale def = Locale.getDefault();
        // 完整 Locale（如 zh_CN、en_GB）小写后与语言文件命名一致
        String full = def.toString().toLowerCase(Locale.ROOT);
        if (isSupportedLanguage(full)) {
            return full;
        }
        // 语言主码（如 zh、en），仅当存在对应语言文件时使用
        String main = def.getLanguage().toLowerCase(Locale.ROOT);
        if (isSupportedLanguage(main)) {
            return main;
        }
        // 语言族回退：在已有语言文件中找 <主码>_ 前缀（如 zh_tw → zh_cn、en_us → en_gb）
        String family = findLanguageFamily(main);
        if (family != null) {
            return family;
        }
        return "en_gb";
    }

    /**
     * 语言族回退：在外部 lang/ 目录与内置语言中查找以语言主码开头（{@code <main>_}）的语言文件。
     *
     * @return 匹配的语言代码，无匹配返回 null
     */
    private static String findLanguageFamily(String main) {
        if (main == null || main.isEmpty()) {
            return null;
        }
        // 外部 lang/ 目录优先（管理员新增的语言文件可参与语言族匹配）
        if (langDir != null && Files.isDirectory(langDir)) {
            try (var stream = Files.list(langDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(main + "_") && name.endsWith(".yml")) {
                        return name.substring(0, name.length() - 4);
                    }
                }
            } catch (IOException e) {
                LOGGER.fine("Failed to list lang dir for language family: " + e.getMessage());
            }
        }
        // 内置语言兜底
        for (String builtin : BUILTIN_LANGS) {
            if (builtin.startsWith(main + "_")) {
                return builtin;
            }
        }
        return null;
    }

    // ==================== 内部方法 ====================

    /**
     * 从 JAR 内置资源提取默认语言文件到数据目录，资源缺失时写入默认内容。
     */
    private static void extractBuiltinLanguages() throws IOException {
        for (String lang : BUILTIN_LANGS) {
            Path target = langDir.resolve(lang + ".yml");
            if (!Files.exists(target)) {
                String resourcePath = RESOURCE_PREFIX + lang + ".yml";
                try (InputStream in = Messages.class.getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        Files.copy(in, target);
                    } else {
                        writeDefaultLanguageFile(lang, target);
                    }
                }
            }
        }
    }

    /**
     * 从外部文件加载消息，文件不存在时回退到 JAR 内置资源。
     */
    private static void loadMessages(String lang) {
        messages.clear();
        Path langFile = langDir.resolve(lang + ".yml");
        if (Files.exists(langFile)) {
            loadFromYamlFile(langFile);
        } else {
            String resourcePath = RESOURCE_PREFIX + lang + ".yml";
            try (InputStream in = Messages.class.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    loadFromStream(in);
                }
            } catch (IOException e) {
                LOGGER.warning("Failed to load resource " + resourcePath + ": " + e.getMessage());
            }
        }
        verifyKeys();
    }

    /**
     * 从 YAML 文件加载（强制 UTF-8 编码，避免中文乱码）。
     */
    private static void loadFromYamlFile(Path file) {
        try {
            loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load language file: " + file, e);
        }
    }

    /**
     * 从 InputStream 加载（UTF-8 编码）。
     */
    private static void loadFromStream(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, len);
                }
            }
            loadFromString(sb.toString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load language from stream", e);
        }
    }

    /**
     * 从字符串加载消息（key: value 解析，跳过注释与空行）。
     */
    private static void loadFromString(String content) {
        try (Reader reader = new StringReader(content)) {
            Properties props = new Properties();
            props.load(reader);
            props.forEach((key, value) -> messages.put(key.toString(), stripQuotes(value.toString())));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to parse language content", e);
        }
    }

    /** 去掉 YAML 风格的成对双引号（Properties 解析不会自动去除）。 */
    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return value;
    }

    /** 替换 {0}, {1} 等占位符。 */
    private static String format(String text, Object... args) {
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }

    /**
     * 验证所有静态字段对应的键是否已加载，缺失键使用默认文本兜底。
     */
    private static void verifyKeys() {
        // 玩家消息
        SUCCESS = messages.getOrDefault("success", "&aPicklock successful!");
        FAIL = messages.getOrDefault("fail", "&cPicklock failed!");
        START_PICKING = messages.getOrDefault("startPicking", "&aStart picking!");
        CANCEL_PICKING = messages.getOrDefault("cancelPicking", "&aPicklocking cancelled!");
        RULE = messages.getOrDefault("rule", "&aPicklock started! Click when the green cursor is inside the red zone!");
        LOCKED_IT = messages.getOrDefault("lockedIt", "&aLocked successfully");
        CHEST_LOCKED = messages.getOrDefault("chestLocked", "&cThis chest is locked!");
        GAME_TIMEOUT = messages.getOrDefault("gameTimeout", "&cPicklock timed out!");
        COOLDOWN = messages.getOrDefault("cooldown", "&cToo fast! Please wait {0} seconds");
        NO_PERMISSION = messages.getOrDefault("noPermission", "&cYou do not have permission to use this command!");
        PLAYER_NOT_FOUND = messages.getOrDefault("playerNotFound", "&cPlayer not found or offline!");
        INVALID_ID = messages.getOrDefault("invalidId", "&cInvalid item ID: {0}");
        INVALID_AMOUNT = messages.getOrDefault("invalidAmount", "&cInvalid amount!");
        GIVEN_ITEM = messages.getOrDefault("givenItem", "&aGiven {0} {1} to {2}");
        SETITEM_SUCCESS = messages.getOrDefault("setitemSuccess", "&aThe item in your hand is now a {0}");
        SETITEM_NO_ITEM = messages.getOrDefault("setitemNoItem", "&cYou must hold an item in your hand!");
        CHECK_PDC = messages.getOrDefault("checkPdc", "&aItem info:\n&e  ID: &f{0}\n&e  Type: &f{1}");
        CHECK_KEY_INFO = messages.getOrDefault("checkKeyInfo", "&e  Paired lock: &f{0}\n&e  Lock token: &f{1}");
        NONE = messages.getOrDefault("none", "none");
        CHECK_NOT_SPECIAL = messages.getOrDefault("checkNotSpecial", "&cThis is not a special item!");
        UNLOCK_SUCCESS = messages.getOrDefault("unlockSuccess", "&aLock removed and the lock item has been returned!");
        UNLOCK_CONFIRM = messages.getOrDefault("unlockConfirm", "&eInteract again to confirm unlocking!");
        PAIR_SUCCESS = messages.getOrDefault("pairSuccess", "&aKey paired to this lock!");
        PAIR_NOT_LOCKER = messages.getOrDefault("pairNotLocker", "&cOnly the locker can pair a key with this lock!");
        KEY_NOT_MATCHED = messages.getOrDefault("keyNotMatched", "&cThis key does not match this lock!");
        KEY_CHANGED = messages.getOrDefault("keyChanged", "&cThis key is no longer valid; the lock has been changed!");
        PLAYER_ONLY = messages.getOrDefault("playerOnly", "&cThis command can only be used by players!");
        RELOADED = messages.getOrDefault("reloaded", "&aConfiguration reloaded!");
        USAGE = messages.getOrDefault("usage", "&eUsage:\n&e  /chesttheft give <player> <item-id> [amount]\n&e  /chesttheft setitem <item-id>\n&e  /chesttheft check\n&e  /chesttheft reload");

        LORE_TYPE = messages.getOrDefault("loreType", "&7Type: &f{0}");
        LORE_PAIRED = messages.getOrDefault("lorePaired", "&aPaired: &f{0}");
        LORE_NOT_PAIRED = messages.getOrDefault("loreNotPaired", "&7Not paired");

        TYPE_KEY = messages.getOrDefault("typeKey", "Key");
        TYPE_LOCK = messages.getOrDefault("typeLock", "Lock");
        TYPE_PICKER = messages.getOrDefault("typePicker", "Picker");

        loadFormats();

        // 服务器日志
        LOG_ENABLED = messages.getOrDefault("logEnabled", "ChestTheft enabled");
        LOG_LANG_DETECTED = messages.getOrDefault("logLangDetected", "Detected system language {0}, wrote to config: language: {1}");
        LOG_LANG_FILE_MISSING = messages.getOrDefault("logLangFileMissing", "Language file not found: {0}, using built-in defaults");
        LOG_ITEM_DUPLICATE = messages.getOrDefault("logItemDuplicate", "Duplicate item ID: {0} ({1} overrides the previous definition)");
        LOG_ITEM_INVALID_TYPE = messages.getOrDefault("logItemInvalidType", "Item {0} missing a valid type (key / lock / picker), skipped");
        LOG_ITEM_INVALID_MATERIAL = messages.getOrDefault("logItemInvalidMaterial", "Item {0} has invalid material: {1}, using default {2}");
        LOG_DB_INIT = messages.getOrDefault("logDbInit", "Database initialized: {0}");
        LOG_DB_CONNECT_FAIL = messages.getOrDefault("logDbConnectFail", "Failed to connect to database: {0}");
        LOG_DB_CREATE_TABLE_FAIL = messages.getOrDefault("logDbCreateTableFail", "Failed to create table {0}");
        LOG_DB_QUERY_STATE_FAIL = messages.getOrDefault("logDbQueryStateFail", "Failed to query lock state: {0}");
        LOG_DB_LOCK_DUP = messages.getOrDefault("logDbLockDuplicate", "Ignoring duplicate lock record: {0}");
        LOG_DB_LOCKER_FAIL = messages.getOrDefault("logDbLockerFail", "Failed to read locker: {0}");
        LOG_DB_UNLOCK_FAIL = messages.getOrDefault("logDbUnlockFail", "Failed to unlock: {0}");
        LOG_DB_ITEM_FAIL = messages.getOrDefault("logDbItemFail", "Failed to read lock item: {0}");
        LOG_DB_CLOSE_FAIL = messages.getOrDefault("logDbCloseFail", "Failed to close database connection");
        LOG_PACKET_LORE_FAIL = messages.getOrDefault("logPacketLoreFail", "Failed to inject lore into packet: {0}");
        LOG_PACKET_DEBUG = messages.getOrDefault("logPacketDebug", "[PacketManager] packet item NBT: {0} custom_data={1} | id={2}");
        LOG_CONFIG_LOAD_FAIL = messages.getOrDefault("logConfigLoadFail", "Failed to load config: {0}");
        LOG_CONFIG_UPGRADE_FAILED = messages.getOrDefault("logConfigUpgradeFailed", "Failed to upgrade config: {0}");
        LOG_CONFIG_UPGRADE_DONE = messages.getOrDefault("logConfigUpgradeDone", "Config upgraded: {0} new options added (v{1})");
        LOG_LANG_WRITE_FAIL = messages.getOrDefault("logLangWriteFail", "Failed to write language {0} to config: {1}");
        LOG_CONFIG_LOADED_DEBUG = messages.getOrDefault("logConfigLoadedDebug", "Config loaded: db={0} debug={1} lang={2}");
    }

    /**
     * 语言文件不存在且 JAR 资源缺失时，生成默认语言文件兜底。
     */
    private static void writeDefaultLanguageFile(String lang, Path target) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ChestTheft Language File\n");
        sb.append("# Language: ").append(lang).append("\n\n");
        for (Map.Entry<String, String> e : defaultFileContent(lang).entrySet()) {
            sb.append(e.getKey()).append(": \"").append(e.getValue().replace("\n", "\\n")).append("\"\n");
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 默认语言文件内容（与 resources/lang/ 下的内置文件保持一致）。 */
    private static Map<String, String> defaultFileContent(String lang) {
        Map<String, String> map = new LinkedHashMap<>();
        if ("zh_cn".equals(lang)) {
            map.put("success", "&a撬锁成功！");
            map.put("fail", "&c撬锁失败！");
            map.put("startPicking", "&a开始撬锁！");
            map.put("cancelPicking", "&a已取消撬锁！");
            map.put("rule", "&a撬锁开始！当绿色光标走到红色区域时点击鼠标！");
            map.put("lockedIt", "&a上锁成功");
            map.put("chestLocked", "&c这个箱子已上锁！");
            map.put("gameTimeout", "&c撬锁超时！");
            map.put("cooldown", "&c操作太频繁，请等待 {0} 秒");
            map.put("noPermission", "&c你没有权限使用此命令！");
            map.put("playerNotFound", "&c玩家不存在或不在线！");
            map.put("invalidId", "&c无效的物品ID: {0}");
            map.put("invalidAmount", "&c无效的数量！");
            map.put("givenItem", "&a已给予 {0} 个 {1} 给 {2}");
            map.put("setitemSuccess", "&a已将手中的物品设置为 {0}");
            map.put("setitemNoItem", "&c请手持物品后再试！");
            map.put("checkPdc", "&a物品信息:\n&e  ID: &f{0}\n&e  类型: &f{1}");
            map.put("checkKeyInfo", "&e  配对锁: &f{0}\n&e  锁凭证: &f{1}");
            map.put("none", "无");
            map.put("checkNotSpecial", "&c这不是一个特殊物品！");
            map.put("unlockSuccess", "&a已卸下锁并返还锁物品");
            map.put("unlockConfirm", "&e再次交互以确认卸锁！");
            map.put("pairSuccess", "&a钥匙已与这把锁配对！");
            map.put("pairNotLocker", "&c该锁还没有配对的钥匙，只有上锁者可以配对钥匙！");
            map.put("keyNotMatched", "&c这把钥匙与这把锁不匹配！");
            map.put("playerOnly", "&c该命令只能由玩家执行！");
            map.put("reloaded", "&a配置已重载");
            map.put("usage", "&e用法:\n&e  /chesttheft give <玩家> <物品ID> [数量]\n&e  /chesttheft setitem <物品ID>\n&e  /chesttheft check\n&e  /chesttheft reload");
            map.put("loreType", "&7类型: &f{0}");
            map.put("lorePaired", "&a已配对: &f{0}");
            map.put("loreNotPaired", "&7未配对");
            map.put("typeKey", "钥匙");
            map.put("typeLock", "锁");
            map.put("typePicker", "撬锁器");
            map.put("logEnabled", "ChestTheft 已启用");
            map.put("logLangDetected", "检测到系统语言 {0}，已写入 config: language: {1}");
            map.put("logLangFileMissing", "语言文件不存在: {0}，将使用内置默认文本");
            map.put("logItemDuplicate", "重复的物品ID: {0}（{1} 覆盖之前的定义）");
            map.put("logItemInvalidType", "物品 {0} 缺少有效的 type（key / lock / picker），已跳过");
            map.put("logItemInvalidMaterial", "物品 {0} 的材质无效: {1}，使用默认值 {2}");
            map.put("logDbInit", "数据库已初始化: {0}");
            map.put("logDbConnectFail", "数据库连接失败: {0}");
            map.put("logDbCreateTableFail", "无法创建表 {0}");
            map.put("logDbQueryStateFail", "查询锁定状态失败: {0}");
            map.put("logDbLockDuplicate", "上锁时忽略重复记录: {0}");
            map.put("logDbLockerFail", "读取上锁者失败: {0}");
            map.put("logDbUnlockFail", "解锁失败: {0}");
            map.put("logDbItemFail", "读取锁物品失败: {0}");
            map.put("logDbCloseFail", "关闭数据库连接失败");
            map.put("logPacketLoreFail", "为数据包注入 Lore 失败: {0}");
            map.put("logPacketDebug", "[PacketManager] 包物品 NBT: {0} custom_data={1} | id={2}");
            map.put("logConfigLoadFail", "加载配置失败: {0}");
            map.put("logConfigUpgradeFailed", "配置升级失败: {0}");
            map.put("logConfigUpgradeDone", "配置已升级: 新增 {0} 个配置项 (v{1})");
            map.put("logLangWriteFail", "写入语言 {0} 到配置失败: {1}");
            map.put("logConfigLoadedDebug", "配置已加载: db={0} debug={1} lang={2}");
        } else {
            map.put("success", "&aPicklock successful!");
            map.put("fail", "&cPicklock failed!");
            map.put("startPicking", "&aStart picking!");
            map.put("cancelPicking", "&aPicklocking cancelled!");
            map.put("rule", "&aPicklock started! Click when the green cursor is inside the red zone!");
            map.put("lockedIt", "&aLocked successfully");
            map.put("chestLocked", "&cThis chest is locked!");
            map.put("gameTimeout", "&cPicklock timed out!");
            map.put("cooldown", "&cToo fast! Please wait {0} seconds");
            map.put("noPermission", "&cYou do not have permission to use this command!");
            map.put("playerNotFound", "&cPlayer not found or offline!");
            map.put("invalidId", "&cInvalid item ID: {0}");
            map.put("invalidAmount", "&cInvalid amount!");
            map.put("givenItem", "&aGiven {0} {1} to {2}");
            map.put("setitemSuccess", "&aThe item in your hand is now a {0}");
            map.put("setitemNoItem", "&cYou must hold an item in your hand!");
            map.put("checkPdc", "&aItem info:\n&e  ID: &f{0}\n&e  Type: &f{1}");
            map.put("checkKeyInfo", "&e  Paired lock: &f{0}\n&e  Lock token: &f{1}");
            map.put("none", "none");
            map.put("checkNotSpecial", "&cThis is not a special item!");
            map.put("unlockSuccess", "&aLock removed and the lock item has been returned!");
            map.put("unlockConfirm", "&eInteract again to confirm unlocking!");
            map.put("pairSuccess", "&aKey paired to this lock!");
            map.put("pairNotLocker", "&cOnly the locker can pair a key with this lock!");
            map.put("keyNotMatched", "&cThis key does not match this lock!");
            map.put("keyChanged", "&cThis key is no longer valid; the lock has been changed!");
            map.put("playerOnly", "&cThis command can only be used by players!");
            map.put("reloaded", "&aConfiguration reloaded!");
            map.put("usage", "&eUsage:\n&e  /chesttheft give <player> <item-id> [amount]\n&e  /chesttheft setitem <item-id>\n&e  /chesttheft check\n&e  /chesttheft reload");
            map.put("loreType", "&7Type: &f{0}");
            map.put("lorePaired", "&aPaired: &f{0}");
            map.put("loreNotPaired", "&7Not paired");
            map.put("typeKey", "Key");
            map.put("typeLock", "Lock");
            map.put("typePicker", "Picker");
            map.put("logEnabled", "ChestTheft enabled");
            map.put("logLangDetected", "Detected system language {0}, wrote to config: language: {1}");
            map.put("logLangFileMissing", "Language file not found: {0}, using built-in defaults");
            map.put("logItemDuplicate", "Duplicate item ID: {0} ({1} overrides the previous definition)");
            map.put("logItemInvalidType", "Item {0} missing a valid type (key / lock / picker), skipped");
            map.put("logItemInvalidMaterial", "Item {0} has invalid material: {1}, using default {2}");
            map.put("logDbInit", "Database initialized: {0}");
            map.put("logDbConnectFail", "Failed to connect to database: {0}");
            map.put("logDbCreateTableFail", "Failed to create table {0}");
            map.put("logDbQueryStateFail", "Failed to query lock state: {0}");
            map.put("logDbLockDuplicate", "Ignoring duplicate lock record: {0}");
            map.put("logDbLockerFail", "Failed to read locker: {0}");
            map.put("logDbUnlockFail", "Failed to unlock: {0}");
            map.put("logDbItemFail", "Failed to read lock item: {0}");
            map.put("logDbCloseFail", "Failed to close database connection");
            map.put("logPacketLoreFail", "Failed to inject lore into packet: {0}");
            map.put("logPacketDebug", "[PacketManager] packet item NBT: {0} custom_data={1} | id={2}");
            map.put("logConfigLoadFail", "Failed to load config: {0}");
            map.put("logConfigUpgradeFailed", "Failed to upgrade config: {0}");
            map.put("logConfigUpgradeDone", "Config upgraded: {0} new options added (v{1})");
            map.put("logLangWriteFail", "Failed to write language {0} to config: {1}");
            map.put("logConfigLoadedDebug", "Config loaded: db={0} debug={1} lang={2}");
        }
        return map;
    }
}
