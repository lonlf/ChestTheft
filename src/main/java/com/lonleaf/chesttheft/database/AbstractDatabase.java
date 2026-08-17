package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class AbstractDatabase implements Database {
    protected static final String TABLE = "chest_data";

    protected final Logger logger;
    protected final boolean debug;
    protected Connection connection;

    protected AbstractDatabase(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    /** 返回主键列定义（区分数据库方言）。 */
    protected abstract String idColumn();

    protected final void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id " + idColumn() + ","
                + "world VARCHAR(255) NOT NULL,"
                + "x INT NOT NULL,"
                + "y INT NOT NULL,"
                + "z INT NOT NULL,"
                + "lock_item TEXT NOT NULL DEFAULT '',"
                + "locker_uuid VARCHAR(36) NOT NULL DEFAULT '',"
                + "lock_token VARCHAR(36) NOT NULL DEFAULT '',"
                + "paired_count INT NOT NULL DEFAULT 0,"
                + "lock_level INT NOT NULL DEFAULT 0,"
                + "UNIQUE(world, x, y, z)"
                + ")";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CREATE_TABLE_FAIL, TABLE), e);
        }
        // 旧表迁移：为缺失的列补充（列已存在时忽略错误）
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN lock_item TEXT NOT NULL DEFAULT ''");
        } catch (SQLException ignored) {
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN locker_uuid VARCHAR(36) NOT NULL DEFAULT ''");
        } catch (SQLException ignored) {
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN lock_level INT NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    /**
     * 物品序列化：优先 NBT 字节格式（Paper 反射 API，保留 PDC），失败时回退 Yaml（兼容旧数据）。
     */
    protected static String serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        try {
            Method m = ItemStack.class.getMethod("serializeAsBytes");
            byte[] bytes = (byte[]) m.invoke(item);
            return "NBT:" + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("item", item);
            return config.saveToString();
        }
    }

    /** 从存储字符串还原物品，空数据返回 null；兼容 NBT 字节与旧版 Yaml 两种格式。 */
    protected static ItemStack deserializeItem(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            if (data.startsWith("NBT:")) {
                Method m = ItemStack.class.getMethod("deserializeBytes", byte[].class);
                return (ItemStack) m.invoke(null, (Object) Base64.getDecoder().decode(data.substring(4)));
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(data));
            return config.getItemStack("item");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isLocked(BlockLocation location) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
        }
        return false;
    }

    @Override
    public boolean lock(BlockLocation location, ItemStack lockItem, String lockerUuid, String token, int level) {
        String sql = "INSERT INTO " + TABLE + " (world, x, y, z, lock_item, locker_uuid, lock_token, lock_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            ps.setString(5, serializeItem(lockItem));
            ps.setString(6, lockerUuid);
            ps.setString(7, token);
            ps.setInt(8, level);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            // UNIQUE 约束冲突说明已上锁，属正常情况
            if (debug) logger.log(Level.INFO, Messages.getLog(Messages.LOG_DB_LOCK_DUP, location), e);
            return false;
        }
    }

    @Override
    public int getLockLevel(BlockLocation location) {
        String sql = "SELECT lock_level FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
        }
        return 0;
    }

    @Override
    public String getLockToken(BlockLocation location) {
        String sql = "SELECT lock_token FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String token = rs.getString("lock_token");
                    return token == null || token.isEmpty() ? null : token;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
        }
        return null;
    }

    @Override
    public String getLocker(BlockLocation location) {
        String sql = "SELECT locker_uuid FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String uuid = rs.getString("locker_uuid");
                    return uuid == null || uuid.isEmpty() ? null : uuid;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_LOCKER_FAIL, location), e);
        }
        return null;
    }

    @Override
    public boolean hasPairedKey(BlockLocation location) {
        String sql = "SELECT paired_count FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
        }
        return false;
    }

    @Override
    public void increasePairedCount(BlockLocation location) {
        String sql = "UPDATE " + TABLE + " SET paired_count = paired_count + 1 WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
        }
    }

    @Override
    public ItemStack unlock(BlockLocation location) {
        ItemStack lockItem = getLockItem(location);
        String sql = "DELETE FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_UNLOCK_FAIL, location), e);
        }
        return lockItem;
    }

    @Override
    public ItemStack getLockItem(BlockLocation location) {
        String sql = "SELECT lock_item FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, location.getWorld());
            ps.setInt(2, location.getX());
            ps.setInt(3, location.getY());
            ps.setInt(4, location.getZ());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return deserializeItem(rs.getString("lock_item"));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_ITEM_FAIL, location), e);
        }
        return null;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CLOSE_FAIL), e);
            }
        }
    }
}
