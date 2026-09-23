package com.lonleaf.chesttheft.trigger;

import com.lonleaf.chesttheft.config.Messages;

import java.util.List;
import java.util.logging.Logger;

/**
 * 触发器：以 id 为主键的配置项，由触发类型与一组动作组成。
 */
public record Trigger(String id, TriggerType type, List<Action> actions) {

    private static final Logger LOGGER = Logger.getLogger(Trigger.class.getName());

    public void fire(TriggerContext context) {
        for (Action action : actions) {
            try {
                action.trigger(context);
            } catch (Exception e) {
                // 动作异常隔离：配置错误（非法音效/药水名等）只跳过该动作，
                // 不中断其余动作，也不让异常上抛导致 endGame/unlockGlobally 等后续流程被跳过
                LOGGER.warning(Messages.getLog(Messages.LOG_TRIGGER_ACTION_FAIL, id, e.getMessage()));
            }
        }
    }
}
