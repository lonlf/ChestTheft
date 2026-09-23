package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 实现：基于 HikariCP 连接池（连接超时、断线后自动重建，避免数据库功能永久失效）。
 */
public class MySQLDatabase extends AbstractDatabase {

    public MySQLDatabase(String ip, String port, String database, String user, String password,
                         boolean useSSL, String timeZone, Logger logger, boolean debug) {
        super(logger, debug);
        // serverTimezone 可能含 '+'（如 GMT+8）：URL query 中 '+' 会被解码为空格导致驱动取错时区，
        // 拼 URL 前编码为 %2B（时区串本身无空格，replace 即可，无需完整 URL 编码）
        String url = "jdbc:mysql://" + ip + ":" + port + "/" + database
                + "?useSSL=" + useSSL + "&serverTimezone=" + timeZone.replace("+", "%2B")
                + "&characterEncoding=utf8"
                + "&connectTimeout=5000&socketTimeout=30000";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(5);
            cfg.setMinimumIdle(1);
            // 获取连接超时 5 秒；初始化失败 5 秒内抛出（快速失败，启动即暴露配置错误）
            cfg.setConnectionTimeout(5000);
            cfg.setInitializationFailTimeout(5000);
            // 连接存活时间略短于 MySQL wait_timeout（默认 8 小时），断线后由池自动重建新连接
            cfg.setMaxLifetime(1500000);
            // 定期发送心跳探测，提前剔除失效连接
            cfg.setKeepaliveTime(300000);
            cfg.setPoolName("ChestTheft-MySQL");
            dataSource = new HikariDataSource(cfg);
            createTable();
        } catch (ClassNotFoundException | RuntimeException e) {
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
