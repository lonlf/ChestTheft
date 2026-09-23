package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.config.Messages;
import org.bukkit.entity.Player;

/**
 * 外部保护交互判定结果：统一入口（{@link ProtectionManager}）对受保护情况下的不同场景
 * （是否启用撬锁、是否保护所有者、是否无所有者保护等）做出决策，
 * 由调用方按决策类型执行（放行 / 阻止并提示 / 接管卸锁）。
 */
public final class InteractDecision {

    public enum Type {
        /** 放行：允许本次交互 / 上锁。 */
        ALLOW,
        /** 阻止：取消本次交互 / 上锁并提示玩家。 */
        DENY,
        /** 保护所有者接管卸锁：取消交互并卸下锁返还上锁者（关闭撬锁时的所有者交互）。 */
        TAKEOVER_UNLOCK
    }

    private final Type type;
    /** 提示消息键；null 表示无需提示。 */
    private final String messageKey;
    /** 提示消息格式键（消息以何种方式展示）。 */
    private final String messageFormatKey;

    private InteractDecision(Type type, String messageKey, String messageFormatKey) {
        this.type = type;
        this.messageKey = messageKey;
        this.messageFormatKey = messageFormatKey;
    }

    /** 放行决策。 */
    public static InteractDecision allow() {
        return new InteractDecision(Type.ALLOW, null, null);
    }

    /** 阻止决策：取消交互并发送指定提示。 */
    public static InteractDecision deny(String messageKey, String messageFormatKey) {
        return new InteractDecision(Type.DENY, messageKey, messageFormatKey);
    }

    /** 接管卸锁决策：取消交互、卸锁返还并提示（保护所有者 + 关闭撬锁）。 */
    public static InteractDecision takeoverUnlock(String messageKey, String messageFormatKey) {
        return new InteractDecision(Type.TAKEOVER_UNLOCK, messageKey, messageFormatKey);
    }

    public Type getType() {
        return type;
    }

    /** 向玩家发送决策附带的消息（无消息时不发送）。 */
    public void sendTo(Player player) {
        if (messageKey != null) {
            Messages.send(player, messageKey, messageFormatKey);
        }
    }
}
