package com.lonleaf.chesttheft.trigger;

/** 触发动作：在触发上下文上执行预设效果（参照 CustomFishing 动作系统）。 */
@FunctionalInterface
public interface Action {
    void trigger(TriggerContext context);
}
