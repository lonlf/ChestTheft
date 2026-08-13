package com.lonleaf.chestTheft.PCD;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import static com.lonleaf.chestTheft.ChestTheft.key;

public class ItemPCDUtil {
    public static void setThisLock(ItemStack  itemStack){
        ItemMeta meta = itemStack.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,"lock");
        itemStack.setItemMeta(meta);
    }
    public static void setThisKey(ItemStack itemStack){
        ItemMeta meta = itemStack.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,"key");
        itemStack.setItemMeta(meta);
    }
    public static void setThisPicker(ItemStack itemStack){
        ItemMeta meta = itemStack.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING,"picker");
        itemStack.setItemMeta(meta);
    }
    public static boolean isLock(ItemStack itemStack){
        ItemMeta meta = itemStack.getItemMeta();
        String itemId = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (itemId != null && itemId.equals("lock")) {
            return true;
        }
        return false;
    }
    public static boolean isKey(ItemStack itemStack){
        ItemMeta meta = itemStack.getItemMeta();
        String itemId = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (itemId != null && itemId.equals("key")) {
            return true;
        }
        return false;
    }
    public static boolean isPicker(ItemStack itemStack){
        ItemMeta meta = itemStack.getItemMeta();
        String itemId = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (itemId != null && itemId.equals("picker")) {
            return true;
        }
        return false;
    }
}
