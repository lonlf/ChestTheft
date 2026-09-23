package com.lonleaf.chesttheft.database;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.model.BlockLocation;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class AbstractDatabase implements Database {
    protected static final String TABLE = "chest_data";
    /** 临时授权记录表：记录持久化型保护插件打开容器的临时权限，崩溃后启动清理残留。 */
    protected static final String TEMP_GRANT_TABLE = "temp_grants";

    protected final Logger logger;
    protected final boolean debug;
    /** HikariCP 连接池：每次操作独立取用连接，finally 归还；断线后由池自动重建。 */
    protected HikariDataSource dataSource;

    protected AbstractDatabase(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    /** 从连接池获取连接（操作完成后由调用方 try-with-resources 归还）。 */
    protected final Connection getConnection() throws SQLException {
        return dataSource.getConnection();
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
                + "lock_item TEXT NOT NULL,"   // 无默认值：MySQL 8.0.13+ 禁止 TEXT 列字面量默认值（写 DEFAULT '' 会建表失败），写入路径均显式传值
                + "locker_uuid VARCHAR(36) NOT NULL DEFAULT '',"
                + "lock_token VARCHAR(36) NOT NULL DEFAULT '',"
                + "paired_count INT NOT NULL DEFAULT 0,"
                + "lock_level INT NOT NULL DEFAULT 0,"
                + "UNIQUE(world, x, y, z)"
                + ")";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CREATE_TABLE_FAIL, TABLE), e);
            // 建表失败必须中止启用：否则会在"表不存在"的状态下运行，锁实际并未生效
            throw new IllegalStateException("Failed to create table " + TABLE, e);
        }
        // 临时授权记录表（崩溃残留清理用，独立一张表）
        String tempSql = "CREATE TABLE IF NOT EXISTS " + TEMP_GRANT_TABLE + " ("
                + "id " + idColumn() + ","
                + "plugin VARCHAR(32) NOT NULL,"
                + "world VARCHAR(255) NOT NULL,"
                + "x INT NOT NULL,"
                + "y INT NOT NULL,"
                + "z INT NOT NULL,"
                + "player VARCHAR(36) NOT NULL,"
                + "extra TEXT NOT NULL"   // 无默认值：兼容 MySQL（TEXT 列禁止字面量默认值），写入路径均显式传值
                + ")";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(tempSql);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CREATE_TABLE_FAIL, TEMP_GRANT_TABLE), e);
            throw new IllegalStateException("Failed to create table " + TEMP_GRANT_TABLE, e);
        }
        // 临时授权唯一索引（防重复记录）：两方言均用普通 CREATE INDEX，已存在时忽略错误，保证幂等
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE UNIQUE INDEX idx_temp_grant ON " + TEMP_GRANT_TABLE
                    + " (plugin, world, x, y, z, player)");
        } catch (SQLException e) {
            String msg = e.getMessage();
            // SQLite: "index idx_temp_grant already exists"；MySQL: "Duplicate key name 'idx_temp_grant'"
            if (msg == null || !(msg.contains("already exists") || msg.contains("Duplicate key name"))) {
                logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_CREATE_TABLE_FAIL, "idx_temp_grant"), e);
            }
        }
    }

    /** NBT 字节序列化反射方法缓存（Paper API；非 Paper 环境为 null，自动回退 Yaml 序列化）。
     *  getMethod 每次调用成本高，类加载时缓存一次。 */
    private static final Method SERIALIZE_AS_BYTES = findItemStackMethod("serializeAsBytes");
    private static final Method DESERIALIZE_BYTES = findItemStackMethod("deserializeBytes", byte[].class);

    private static Method findItemStackMethod(String name, Class<?>... parameterTypes) {
        try {
            return ItemStack.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null; // 非 Paper 环境：序列化走 Yaml 回退
        }
    }

    /**
     * 物品序列化：优先 NBT 字节格式（Paper 反射 API，保留 PDC），失败时回退 Yaml（兼容旧数据）。
     */
    protected static String serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        if (SERIALIZE_AS_BYTES != null) {
            try {
                byte[] bytes = (byte[]) SERIALIZE_AS_BYTES.invoke(item);
                return "NBT:" + Base64.getEncoder().encodeToString(bytes);
            } catch (Exception e) {
                // 反射调用失败：回退 Yaml（保留兼容性）
            }
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("item", item);
        return config.saveToString();
    }

    /** 从存储字符串还原物品，空数据返回 null；兼容 NBT 字节与旧版 Yaml 两种格式。 */
    protected static ItemStack deserializeItem(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        if (data.startsWith("NBT:")) {
            if (DESERIALIZE_BYTES != null) {
                try {
                    return (ItemStack) DESERIALIZE_BYTES.invoke(null, (Object) Base64.getDecoder().decode(data.substring(4)));
                } catch (Exception e) {
                    return null;
                }
            }
            // 非 Paper 环境无法解码 NBT 字节格式：按数据无效处理
            return null;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(data));
        return config.getItemStack("item");
    }

    @Override
    public boolean isLocked(BlockLocation location) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        // 读取失败必须 fail-closed：返回 false 会让数据库抖动期间所有锁失效（任何人可开任何箱子）
        return true;
    }

    @Override
    public boolean lock(BlockLocation location, ItemStack lockItem, String lockerUuid, String token, int level) {
        String sql = "INSERT INTO " + TABLE + " (world, x, y, z, lock_item, locker_uuid, lock_token, lock_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
            if (isUniqueViolation(e)) {
                // 唯一约束冲突 = 该位置已上锁，属正常情况
                if (debug) logger.log(Level.INFO, Messages.getLog(Messages.LOG_DB_LOCK_DUP, location), e);
            } else {
                // 真实错误（断线/建表失败等）：记 SEVERE，调用方不应消耗锁物品
                logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_LOCK_FAIL, location), e);
            }
            return false;
        }
    }

    /** 唯一约束冲突判定：MySQL 1062/23000、SQLite 19/23505。 */
    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        int code = e.getErrorCode();
        return "23000".equals(state) || "23505".equals(state) || code == 1062 || code == 19;
    }

    @Override
    public int getLockLevel(BlockLocation location) {
        String sql = "SELECT lock_level FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
    public UnlockResult unlock(BlockLocation location) {
        String selectSql = "SELECT lock_item FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        String deleteSql = "DELETE FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        // 读+删走同一连接，避免原先"两次取连接"之间被重新上锁而删掉新记录
        try (Connection conn = getConnection()) {
            String lockItemData;
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                bindLocation(ps, location);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return UnlockResult.notFound();
                    }
                    lockItemData = rs.getString("lock_item");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                bindLocation(ps, location);
                if (ps.executeUpdate() == 0) {
                    return UnlockResult.notFound();
                }
            }
            return UnlockResult.deleted(deserializeItem(lockItemData));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_UNLOCK_FAIL, location), e);
            return UnlockResult.error();
        }
    }

    /** 按 (world, x, y, z) 绑定四个占位符参数。 */
    private static void bindLocation(PreparedStatement ps, BlockLocation location) throws SQLException {
        ps.setString(1, location.getWorld());
        ps.setInt(2, location.getX());
        ps.setInt(3, location.getY());
        ps.setInt(4, location.getZ());
    }

    @Override
    public List<LockRecord> loadAllLocks() {
        String sql = "SELECT world, x, y, z, lock_item, locker_uuid, lock_token, lock_level, paired_count FROM " + TABLE;
        List<LockRecord> records = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                records.add(new LockRecord(
                        BlockLocation.from(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                        rs.getString("lock_item"), rs.getString("locker_uuid"), rs.getString("lock_token"),
                        rs.getInt("lock_level"), rs.getInt("paired_count")));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, TABLE), e);
            throw new IllegalStateException("Failed to load locks from " + TABLE, e);
        }
        return records;
    }

    @Override
    public ItemStack getLockItem(BlockLocation location) {
        String sql = "SELECT lock_item FROM " + TABLE + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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

    // ==================== 临时授权记录（崩溃残留清理） ====================

    /**
     * 记录一次临时授权：先删后插，保证同一 (插件, 位置, 玩家) 只保留一行（配合唯一索引幂等）；
     * 失败返回 false，调用方应中止授权，避免"已授权但无记录"导致崩溃后残留权限无法清理。
     */
    @Override
    public boolean recordTempGrant(String pluginType, BlockLocation location, UUID playerUuid, String extra) {
        String deleteSql = "DELETE FROM " + TEMP_GRANT_TABLE
                + " WHERE plugin = ? AND world = ? AND x = ? AND y = ? AND z = ? AND player = ?";
        String insertSql = "INSERT INTO " + TEMP_GRANT_TABLE
                + " (plugin, world, x, y, z, player, extra) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement del = conn.prepareStatement(deleteSql);
             PreparedStatement ins = conn.prepareStatement(insertSql)) {
            del.setString(1, pluginType);
            del.setString(2, location.getWorld());
            del.setInt(3, location.getX());
            del.setInt(4, location.getY());
            del.setInt(5, location.getZ());
            del.setString(6, playerUuid.toString());
            del.executeUpdate();
            ins.setString(1, pluginType);
            ins.setString(2, location.getWorld());
            ins.setInt(3, location.getX());
            ins.setInt(4, location.getY());
            ins.setInt(5, location.getZ());
            ins.setString(6, playerUuid.toString());
            ins.setString(7, extra == null ? "" : extra);
            return ins.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
            return false;
        }
    }

    @Override
    public List<TempGrantRecord> getTempGrants(String pluginType) {
        List<TempGrantRecord> records = new ArrayList<>();
        String sql = "SELECT world, x, y, z, player, extra FROM " + TEMP_GRANT_TABLE + " WHERE plugin = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pluginType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BlockLocation location = BlockLocation.from(
                            rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                    records.add(new TempGrantRecord(pluginType, location,
                            UUID.fromString(rs.getString("player")), rs.getString("extra")));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, pluginType), e);
        }
        return records;
    }

    @Override
    public void deleteTempGrant(String pluginType, BlockLocation location, UUID playerUuid) {
        String sql = "DELETE FROM " + TEMP_GRANT_TABLE
                + " WHERE plugin = ? AND world = ? AND x = ? AND y = ? AND z = ? AND player = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pluginType);
            ps.setString(2, location.getWorld());
            ps.setInt(3, location.getX());
            ps.setInt(4, location.getY());
            ps.setInt(5, location.getZ());
            ps.setString(6, playerUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, Messages.getLog(Messages.LOG_DB_QUERY_STATE_FAIL, location), e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
