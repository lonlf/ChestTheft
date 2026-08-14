package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MySQLDatabase extends AbstractDatabase {

    public MySQLDatabase(String ip, String port, String database, String user, String password,
                         boolean useSSL, String timeZone, Logger logger, boolean debug) {
        super(logger, debug);
        String url = "jdbc:mysql://" + ip + ":" + port + "/" + database
                + "?useSSL=" + useSSL + "&serverTimezone=" + timeZone + "&characterEncoding=utf8";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(url, user, password);
            createTable();
        } catch (ClassNotFoundException | SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CONNECT_FAIL, url), e);
            throw new IllegalStateException(Messages.getLog(Messages.LOG_DB_CONNECT_FAIL, url), e);
        }
    }

    @Override
    protected String idColumn() {
        return "INT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    public void init() {
        // 构造时已建表
    }
}
