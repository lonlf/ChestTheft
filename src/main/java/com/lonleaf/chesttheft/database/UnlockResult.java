package com.lonleaf.chesttheft.database;

import org.bukkit.inventory.ItemStack;

/** 卸锁结果三态：区分"已删除（可返还物品）/ 无记录 / 数据库失败"，避免 null 二义性导致白送锁物品。 */
public final class UnlockResult {

    public enum Status { DELETED, NOT_FOUND, ERROR }

    private static final UnlockResult NOT_FOUND = new UnlockResult(Status.NOT_FOUND, null);
    private static final UnlockResult ERROR = new UnlockResult(Status.ERROR, null);

    private final Status status;
    private final ItemStack item;

    private UnlockResult(Status status, ItemStack item) {
        this.status = status;
        this.item = item;
    }

    public static UnlockResult deleted(ItemStack item) {
        return new UnlockResult(Status.DELETED, item);
    }

    public static UnlockResult notFound() {
        return NOT_FOUND;
    }

    public static UnlockResult error() {
        return ERROR;
    }

    public Status getStatus() {
        return status;
    }

    /** 仅 DELETED 时有值，且可能为 null（旧数据未保存锁物品）。 */
    public ItemStack getItem() {
        return item;
    }

    public boolean isDeleted() {
        return status == Status.DELETED;
    }

    public boolean isNotFound() {
        return status == Status.NOT_FOUND;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }
}
