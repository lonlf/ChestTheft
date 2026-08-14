package com.lonleaf.chesttheft.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Objects;

/**
 * 上锁方块位置值对象，使用方块坐标（世界名 + blockX/Y/Z）表示。
 */
public final class BlockLocation {
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private BlockLocation(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockLocation from(Block block) {
        return new BlockLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockLocation from(Location location) {
        return new BlockLocation(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** 从字符串解析位置，格式为 world,x,y,z；格式非法时返回 null。 */
    public static BlockLocation parse(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String[] parts = data.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new BlockLocation(parts[0], Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public World toWorld() {
        return org.bukkit.Bukkit.getWorld(world);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockLocation)) return false;
        BlockLocation that = (BlockLocation) o;
        return x == that.x && y == that.y && z == that.z && world.equals(that.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z);
    }

    @Override
    public String toString() {
        return world + "," + x + "," + y + "," + z;
    }
}
