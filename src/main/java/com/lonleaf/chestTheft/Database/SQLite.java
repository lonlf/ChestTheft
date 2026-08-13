package com.lonleaf.chestTheft.Database;

import java.sql.*;

public class SQLite {
    private Connection connection;
    private final String url;

    public SQLite(String path,String databaseName) {
        this.url = "jdbc:sqlite:" + path + databaseName + ".db";
        connect();
    }


    private void connect() {
        try {
            connection = DriverManager.getConnection(url);
            createTable();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    private void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS chest_data ("
                + "id INT AUTO_INCREMENT PRIMARY KEY,"
                + "world VARCHAR(255) NOT NULL,"
                + "location VARCHAR(255) NOT NULL"
                + ");";

        // 创建表
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 检查数据是否存在的方法
    public boolean checkDataExists(String world, String location) {
        String querySQL = "SELECT COUNT(*) FROM chest_data WHERE world = ? AND location = ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(querySQL)) {
            preparedStatement.setString(1, world);
            preparedStatement.setString(2, location);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // 插入数据的方法
    public void insertData(String world, String location) {
        String insertSQL = "INSERT INTO chest_data (world, location) VALUES (?, ?)";
        try (PreparedStatement preparedStatement = connection.prepareStatement(insertSQL)) {
            preparedStatement.setString(1, world);
            preparedStatement.setString(2, location);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 删除数据的方法
    public void deleteData(String world, String location) {
        String deleteSQL = "DELETE FROM chest_data WHERE world = ? AND location = ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteSQL)) {
            preparedStatement.setString(1, world);
            preparedStatement.setString(2, location);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 更新数据的方法
    public void updateData(String oldWorld, String oldLocation, String newWorld, String newLocation) {
        String updateSQL = "UPDATE chest_data SET world = ?, location = ? WHERE world = ? AND location = ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(updateSQL)) {
            preparedStatement.setString(1, newWorld);
            preparedStatement.setString(2, newLocation);
            preparedStatement.setString(3, oldWorld);
            preparedStatement.setString(4, oldLocation);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
