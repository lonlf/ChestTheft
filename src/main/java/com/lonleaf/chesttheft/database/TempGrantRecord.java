package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;

import java.util.UUID;

/**
 * 一条临时授权记录（领地类 / WorldGuard / NoBuildPlus 打开容器的临时权限）：
 * 插件崩溃后启动时依据表中记录清理残留授权，防止成员/权限混乱。
 */
public final class TempGrantRecord {

    private final String pluginType;
    private final BlockLocation location;
    private final UUID playerUuid;
    private final String extra;

    public TempGrantRecord(String pluginType, BlockLocation location, UUID playerUuid, String extra) {
        this.pluginType = pluginType;
        this.location = location;
        this.playerUuid = playerUuid;
        this.extra = extra;
    }

    /** 产生该记录的插件类型（与保护监听器的 pluginType() 对应）。 */
    public String getPluginType() {
        return pluginType;
    }

    /** 临时授权对应的容器位置。 */
    public BlockLocation getLocation() {
        return location;
    }

    /** 被授予临时权限的玩家。 */
    public UUID getPlayerUuid() {
        return playerUuid;
    }

    /** 撤销所需的插件特定数据（各插件序列化的授权信息），可为空字符串。 */
    public String getExtra() {
        return extra;
    }
}
