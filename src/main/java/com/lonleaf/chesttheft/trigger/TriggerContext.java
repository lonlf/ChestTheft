package com.lonleaf.chesttheft.trigger;

import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.entity.Player;

/**
 * 触发上下文：提供触发动作所需的玩家与目标位置，并统一做占位符替换。
 */
public record TriggerContext(Player player, BlockLocation location) {

    /**
     * 替换占位符 {player}/{world}/{x}/{y}/{z}，未知占位符原样保留。
     */
    public String render(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replace("{player}", player.getName());
        if (location != null) {
            result = result.replace("{world}", location.getWorld())
                    .replace("{x}", String.valueOf(location.getX()))
                    .replace("{y}", String.valueOf(location.getY()))
                    .replace("{z}", String.valueOf(location.getZ()));
        }
        return result;
    }
}
