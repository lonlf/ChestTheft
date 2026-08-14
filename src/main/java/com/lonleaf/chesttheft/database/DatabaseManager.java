package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.ChestTheft;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.PluginConfig;

public class DatabaseManager {
    private final Database database;

    public DatabaseManager(ChestTheft plugin, PluginConfig config) {
        PluginConfig.DataBaseConfig db = config.getDataBaseConfig();
        switch (db.getType()) {
            case MYSQL:
                database = new MySQLDatabase(db.getIp(), db.getPort(), db.getDatabase(),
                        db.getUser(), db.getPassword(), db.isUseSsl(), db.getTimeZone(),
                        plugin.getLogger(), config.isDebug());
                break;
            case SQLITE:
            default:
                database = new SQLiteDatabase(plugin.getDataFolder(), plugin.getLogger(), config.isDebug());
                break;
        }
        database.init();
        plugin.getLogger().info(Messages.getLog(Messages.LOG_DB_INIT, db.getType().name().toLowerCase()));
    }

    public Database getDatabase() {
        return database;
    }

    public void shutdown() {
        database.close();
    }
}
