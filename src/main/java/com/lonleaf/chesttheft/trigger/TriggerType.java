package com.lonleaf.chesttheft.trigger;

/** 触发器类型：对应游戏内 7 种可配置触发的时机。 */
public enum TriggerType {
    SUCCESS("success", "撬锁成功"),
    FAIL("fail", "撬锁失败"),
    CANCEL("cancel", "取消撬锁"),
    INTERRUPTED("interrupted", "撬锁被打断"),
    LOCK("lock", "上锁"),
    KEY_OPEN("key-open", "用钥匙开锁"),
    KEY_PAIR("key-pair", "配对钥匙");

    private final String key;
    private final String displayName;

    TriggerType(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /** 从配置键解析触发器类型，未知类型返回 null。 */
    public static TriggerType fromString(String key) {
        if (key == null) {
            return null;
        }
        for (TriggerType type : values()) {
            if (type.key.equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }
}
