package com.lonleaf.chesttheft.protection;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 外部保护插件适配器：将各保护插件的"保护检测 / 所有者查询 / 打开容器临时授权 / 保护创建回调"
 * 统一为同一套接口，由 {@link ProtectionManager} 统一调度。每个适配器对应一个软依赖保护插件。
 * 仅在其对应插件存在时才会被创建与装配，避免类加载解析插件类型导致 NoClassDefFoundError。
 */
public interface ProtectionAdapter {

    /** 本插件在数据库临时授权表中的类型标识（如 "bolt"、"lwc"、"residence"）。 */
    String pluginType();

    /** 对应保护插件是否已安装（软依赖检测）。 */
    boolean isActive();

    /** 方块是否受本插件保护。 */
    boolean isProtected(Block block);

    /** 保护所有者 UUID；无所有者概念（如 NoBuildPlus 世界 flag 保护）返回 null。 */
    UUID getOwnerUUID(Block block);

    /**
     * 打开受保护容器前的临时授权入口（事件内授权，早于保护插件 NORMAL 检查）：先撤销上一次
     * 残留授权（防止旧授权覆盖新授权导致本次打开失效），再按需授予新授权。
     * 收回由 {@link ProtectionManager} 在打开动作完成后下一 tick 统一调用（revokeAccess），不写库不持久化。
     */
    void grantOpenAccess(Block block, Player player);

    /** 撤销玩家在指定方块上的临时授权（事件内授权收回时调用）。 */
    void revokeAccess(Block block, Player player);

    /** 启动时依据数据库记录清理崩溃残留的临时授权（仅持久化型保护；附件型为空实现）。 */
    void cleanupStale();

    /** 插件禁用时清理全部临时授权（防止授权泄漏）。 */
    void cleanup();

    /**
     * 注册：保存保护创建回调（已上锁箱子被本插件保护时自动卸锁的入口），
     * 并注册必要的插件事件订阅（如 Bolt 保护创建事件 / 领地创建事件）。
     */
    void register(Consumer<Block> protectionCreatedHandler);

    /** 注销：撤销事件订阅并清理临时授权（插件禁用时调用）。 */
    void unregister();
}
