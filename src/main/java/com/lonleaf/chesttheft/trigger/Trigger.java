package com.lonleaf.chesttheft.trigger;

import java.util.List;

/** 触发器：以 id 为主键的配置项，由触发类型与一组动作组成。 */
public class Trigger {
    private final String id;
    private final TriggerType type;
    private final List<Action> actions;

    public Trigger(String id, TriggerType type, List<Action> actions) {
        this.id = id;
        this.type = type;
        this.actions = actions;
    }

    public String getId() {
        return id;
    }

    public TriggerType getType() {
        return type;
    }

    public List<Action> getActions() {
        return actions;
    }

    public void fire(TriggerContext context) {
        for (Action action : actions) {
            action.trigger(context);
        }
    }
}
