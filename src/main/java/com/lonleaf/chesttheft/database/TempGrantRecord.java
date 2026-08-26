package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.model.BlockLocation;

import java.util.UUID;

/**
 * 临时授权数据库记录：崩溃后启动清理（cleanupStale）依据该记录恢复各保护插件的残留授权。
 * extra 为授权前的原状态序列化（各适配器自定义格式，用于恢复原值）。
 */
public class TempGrantRecord {

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

    public String getPluginType() {
        return pluginType;
    }

    public BlockLocation getLocation() {
        return location;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getExtra() {
        return extra;
    }
}
