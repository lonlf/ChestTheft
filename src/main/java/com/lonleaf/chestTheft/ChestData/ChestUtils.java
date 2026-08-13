package com.lonleaf.chestTheft.ChestData;

import com.lonleaf.chestTheft.Database.DatabaseUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public class ChestUtils {
    public static boolean isLocked(Block chest){
        World world = chest.getWorld();
        Location location = chest.getLocation();
        return DatabaseUtils.checkDataExists(world,location);
    }

    public static void unLock(Block chest){
        World world = chest.getWorld();
        Location location = chest.getLocation();
        DatabaseUtils.deleteData(world,location);
    }

    public static void lock(Block chest){
        World world = chest.getWorld();
        Location location = chest.getLocation();
        DatabaseUtils.insertData(world,location);
    }
}
