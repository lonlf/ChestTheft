package com.lonleaf.chestTheft.Database;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import static com.lonleaf.chestTheft.ChestTheft.instance;

public class DatabaseUtils {
    public static byte whichDataBase;
    public static MySQL mySQL;
    public static SQLite sqLite;

    public static void initDataBase(MySQL mySQL){
        DatabaseUtils.mySQL = mySQL;
        whichDataBase = 1;
    }

    public static void initDataBase(SQLite sqLite){
        DatabaseUtils.sqLite = sqLite;
        whichDataBase = 0;
    }

    public static boolean checkDataExists(World world, Location location){
        if(whichDataBase == 0){
           return sqLite.checkDataExists(world.getName(),location.toString());
        }else if (whichDataBase == 1){
           return mySQL.checkDataExists(world.getName(),location.toString());
        }
        Bukkit.getLogger().warning(instance.language("noDataBaseError"));
        return false;
    }

    public static void insertData(World world, Location location){
        if(whichDataBase == 0){
            sqLite.insertData(world.getName(),location.toString());
        }else if (whichDataBase == 1){
            mySQL.insertData(world.getName(),location.toString());
        }
    }

    public static void deleteData(World world, Location location){
        if(whichDataBase == 0){
            sqLite.deleteData(world.getName(),location.toString());
        }else if (whichDataBase == 1){
            mySQL.deleteData(world.getName(),location.toString());
        }
    }

    public static void updateData(World oldWorld, Location oldLocation, World world, Location location){
        if(whichDataBase == 0){
            sqLite.updateData(oldWorld.getName(),oldLocation.toString(),world.getName(),location.toString());
        }else if (whichDataBase == 1){
            mySQL.updateData(oldWorld.getName(),oldLocation.toString(),world.getName(),location.toString());
        }
    }

    public static void close(){
        if(whichDataBase == 0){
            sqLite.close();
        }else if (whichDataBase == 1){
            mySQL.close();
        }
    }

}
