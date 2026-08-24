package com.lonleaf.chesttheft.trigger;

import java.util.List;

/**
 * 触发器：以 id 为主键的配置项，由触发类型与一组动作组成。
 */
public record Trigger(String id, TriggerType type, List<Action> actions) {

    public void fire(TriggerContext context) {
        for (Action action : actions) {
            action.trigger(context);
        }
    }
}
