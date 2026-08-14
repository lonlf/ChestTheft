package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;

import java.io.File;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            connection = DriverManager.getConnection(url);
            createTable();
        } catch (ClassNotFoundException | SQLException e) {
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
