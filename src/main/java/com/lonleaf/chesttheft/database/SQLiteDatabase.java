package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 实现：基于 HikariCP 连接池（单连接，SQLite 只允许单写者）。
 */
public class SQLiteDatabase extends AbstractDatabase {
    private final String url;

    public SQLiteDatabase(File dataFolder, Logger logger, boolean debug) {
        super(logger, debug);
        this.url = "jdbc:sqlite:" + new File(dataFolder, "chest_data.db").getAbsolutePath();
        connect();
    }

    private void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            // SQLite 只允许一个写连接，池保持单连接即可
            cfg.setMaximumPoolSize(1);
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(5000);
            cfg.setInitializationFailTimeout(5000);
            cfg.setPoolName("ChestTheft-SQLite");
            cfg.setConnectionTestQuery("SELECT 1");
            dataSource = new HikariDataSource(cfg);
            createTable();
        } catch (ClassNotFoundException | RuntimeException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CONNECT_FAIL, url), e);
            throw new IllegalStateException(Messages.getLog(Messages.LOG_DB_CONNECT_FAIL, url), e);
        }
    }

    @Override
    protected String idColumn() {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public void init() {
        // SQLite 在连接时已建表
    }
}
