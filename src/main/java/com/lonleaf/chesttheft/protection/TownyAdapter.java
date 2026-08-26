package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.database.Database;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.TownClaimEvent;
import com.palmergames.bukkit.towny.object.PermissionData;
import com.palmergames.bukkit.towny.object.PlayerCache;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.utils.PermissionGUIUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Towny 城镇保护适配器（软依赖）：城镇创建/地块声明自动卸锁 + 打开前事件内临时覆盖地块 SWITCH 权限。
 * 城镇地块覆盖为持久化写入（Towny 数据），授权前落库（记录原覆盖），崩溃后启动清理恢复；
 * 野地权限为纯内存附件（重启即清），不落库。
 */
public class TownyAdapter extends TempAccessAdapter implements Listener {

    /** 是否已注册监听。 */
    private boolean registered = false;

    public TownyAdapter(Plugin plugin, Database database) {
        super(plugin, database);
    }

    @Override
    public String pluginType() {
        return "towny";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("Towny") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isTownyProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return ProtectionUtil.townyOwner(block);
    }

    /** 城镇地块覆盖为持久化写入需要落库（崩溃清理）；野地为内存附件（重启即清）不落库。 */
    @Override
    protected boolean persistGrant(TempGrant grant) {
        return grant instanceof TownyGrant townyGrant && !townyGrant.wild;
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        TownyGrant townyGrant = (TownyGrant) grant;
        if (townyGrant.original == null || townyGrant.original.getPermissionTypes() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (PermissionGUIUtil.SetPermissionType type : townyGrant.original.getPermissionTypes()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(type.name());
        }
        return sb.toString();
    }

    @Override
    protected void revokeFromRecord(Block block, UUID playerUuid, String extra) {
        if (!isActive()) {
            return;
        }
        TownyAPI towny = TownyAPI.getInstance();
        TownBlock townBlock = towny.getTownBlock(block.getLocation());
        if (townBlock == null) {
            return;
        }
        Resident resident = towny.getResident(playerUuid);
        if (resident == null) {
            return;
        }
        Map<Resident, PermissionData> overrides = new HashMap<>(townBlock.getPermissionOverrides());
        // 按玩家名先移除该玩家现有的覆盖条目，避免 Resident 实例变化导致新旧条目并存残留
        removeOverride(overrides, resident.getName());
        if (extra != null && !extra.isEmpty()) {
            // 原本有覆盖：按记录恢复
            PermissionGUIUtil.SetPermissionType[] types = parsePermissionTypes(extra);
            overrides.put(resident, new PermissionData(types, playerUuid.toString()));
        }
        townBlock.setPermissionOverrides(overrides);
    }

    /** 解析序列化的权限类型数组（"SET,NOT_SET,..."），解析失败时回退默认类型。 */
    private PermissionGUIUtil.SetPermissionType[] parsePermissionTypes(String data) {
        try {
            String[] parts = data.split(",", -1);
            PermissionGUIUtil.SetPermissionType[] types = PermissionGUIUtil.getDefaultTypes().clone();
            for (int i = 0; i < parts.length && i < types.length; i++) {
                types[i] = PermissionGUIUtil.SetPermissionType.valueOf(parts[i]);
            }
            return types;
        } catch (IllegalArgumentException e) {
            return PermissionGUIUtil.getDefaultTypes().clone();
        }
    }

    /** 注册 Towny 事件监听（仅 Towny 插件存在时）。 */
    @Override
    public void register(Consumer<Block> protectionCreatedHandler) {
        super.register(protectionCreatedHandler);
        if (isActive() && !registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    @Override
    public void unregister() {
        registered = false;
        cleanup();
    }

    // ==================== 打开容器：临时设置 SWITCH 权限覆盖 ====================

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (!isActive()) {
            return null;
        }
        TownyAPI towny = TownyAPI.getInstance();
        UUID uuid = player.getUniqueId();
        Resident resident = towny.getResident(uuid);
        TownBlock townBlock = towny.getTownBlock(block.getLocation());
        boolean debug = plugin.getConfig().getBoolean("debug", false);
        if (townBlock == null) {
            // 野地（非城镇地块）：容器开关受 towny.wild.switch.<材料> 权限控制（Towny 按
            // actionType.switch + 方块材料名拼接检查，如 towny.wild.switch.CHEST），
            // 玩家已有该权限时无需授权；该权限为纯 Bukkit 权限，授予不依赖玩家是否为 Towny 居民
            // （无居民记录时首次打开依赖 openInventory 绕过拦截，后续右键打开必须在此授权，否则被 Towny 拦下）
            String wildNode = "towny.wild.switch." + block.getType().name();
            boolean hasWild = player.hasPermission(wildNode);
            if (debug) {
                plugin.getLogger().info("[Towny] 野地检查: " + player.getName() + " resident=" + (resident != null)
                        + " node=" + wildNode + " hasWild=" + hasWild);
            }
            if (hasWild) {
                return null;
            }
            return new TownyGrant(null, true);
        }
        if (resident == null) {
            return null;
        }
        PermissionData original = getOverride(townBlock.getPermissionOverrides(), player.getName());
        if (original != null && original.getPermissionTypes() != null
                && original.getPermissionTypes()[TownyPermission.ActionType.SWITCH.getIndex()] == PermissionGUIUtil.SetPermissionType.SET) {
            return null; // 玩家已有 SWITCH 权限
        }
        return new TownyGrant(original, false);
    }

    @Override
    protected void apply(Block block, Player player, TempGrant grant) {
        TownyGrant townyGrant = (TownyGrant) grant;
        if (townyGrant.wild) {
            // 野地：临时授予 towny.wild.switch.<材料> 权限（Towny 实际检查的节点，如 towny.wild.switch.CHEST），
            // 绕过野地保护；关闭/退出时移除附件
            townyGrant.attachment = player.addAttachment(plugin, "towny.wild.switch." + block.getType().name(), true);
            resetTownyCache(player, block);
            return;
        }
        TownyAPI towny = TownyAPI.getInstance();
        TownBlock townBlock = towny.getTownBlock(block.getLocation());
        if (townBlock == null) {
            throw new IllegalStateException("地块消失，无法授予容器权限");
        }
        Resident resident = towny.getResident(player.getUniqueId());
        if (resident == null) {
            throw new IllegalStateException("玩家数据缺失，无法授予容器权限");
        }
        PermissionData original = townyGrant.original;
        // 容器开关对应 SWITCH 动作：保留原覆盖的其他权限值，仅开启 SWITCH（原本无覆盖时用默认类型）
        PermissionGUIUtil.SetPermissionType[] types = original != null && original.getPermissionTypes() != null
                ? original.getPermissionTypes().clone() : PermissionGUIUtil.getDefaultTypes().clone();
        types[TownyPermission.ActionType.SWITCH.getIndex()] = PermissionGUIUtil.SetPermissionType.SET;
        Map<Resident, PermissionData> updated = new HashMap<>(townBlock.getPermissionOverrides());
        removeOverride(updated, player.getName());
        updated.put(resident, new PermissionData(types, player.getName()));
        townBlock.setPermissionOverrides(updated);
        resetTownyCache(player, block);
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        TownyGrant townyGrant = (TownyGrant) grant;
        if (!townyGrant.granted || !isActive()) {
            return;
        }
        if (townyGrant.wild) {
            // 野地：移除临时权限附件（附件不持久化，崩溃后自动失效，无需启动清理）
            if (townyGrant.attachment != null) {
                townyGrant.attachment.remove();
            }
            resetTownyCache(player, block);
            return;
        }
        TownyAPI towny = TownyAPI.getInstance();
        TownBlock townBlock = towny.getTownBlock(block.getLocation());
        if (townBlock == null) {
            return;
        }
        Resident resident = towny.getResident(player.getUniqueId());
        if (resident == null) {
            return;
        }
        Map<Resident, PermissionData> overrides = new HashMap<>(townBlock.getPermissionOverrides());
        // 按玩家名先移除现有条目（避免 Resident 实例变化导致 equals 失效、覆盖残留短路后续授权）
        removeOverride(overrides, player.getName());
        if (townyGrant.original != null) {
            // 原本有覆盖：恢复原值
            overrides.put(resident, townyGrant.original);
        }
        townBlock.setPermissionOverrides(overrides);
        resetTownyCache(player, block);
    }

    /**
     * 重置 Towny 玩家权限缓存：临时授权（野地权限附件 / 地块 SWITCH 覆盖）写入后立即清空 PlayerCache，
     * 使 Towny 的 SWITCH 检查基于最新权限重新计算，而不是命中首次检查的旧缓存值
     * （野地/城镇首次交互未授权时缓存的 false，会持续拦截后续授权成功的打开）。
     */
    private void resetTownyCache(Player player, Block block) {
        if (!isActive()) {
            return;
        }
        try {
            PlayerCache cache = ((Towny) Bukkit.getPluginManager().getPlugin("Towny")).getCache(player);
            if (cache != null) {
                cache.resetAndUpdate(WorldCoord.parseWorldCoord(block));
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("[Towny] 已重置权限缓存: " + player.getName() + " @ "
                            + block.getX() + "," + block.getY() + "," + block.getZ());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("[Towny] 重置权限缓存失败: " + e.getMessage());
        }
    }

    /** 按玩家名读取权限覆盖（避免依赖 Resident 实例 equals，Towny 数据刷新后实例可能变化）。 */
    private PermissionData getOverride(Map<Resident, PermissionData> overrides, String name) {
        for (Map.Entry<Resident, PermissionData> entry : overrides.entrySet()) {
            if (entry.getKey().getName().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 按玩家名移除该玩家的全部权限覆盖条目（同样避免依赖 Resident 实例 equals）。 */
    private void removeOverride(Map<Resident, PermissionData> overrides, String name) {
        overrides.keySet().removeIf(resident -> resident.getName().equalsIgnoreCase(name));
    }

    /** Towny 临时授权记录：携带玩家原有的权限覆盖（撤销时恢复）或野地模式的临时权限附件。 */
    private static final class TownyGrant extends TempGrant {
        /** 玩家原有的权限覆盖；null 表示原本无覆盖（撤销时移除）或野地模式。 */
        final PermissionData original;
        /** 是否为野地模式（通过 towny.wild.switch 权限绕过野地保护，而非地块覆盖）。 */
        final boolean wild;
        /** 野地模式下的临时权限附件（apply 时创建，撤销时移除）。 */
        PermissionAttachment attachment;

        TownyGrant(PermissionData original, boolean wild) {
            super(true);
            this.original = original;
            this.wild = wild;
        }
    }

    // ==================== 城镇创建/声明后自动卸锁 ====================

    /** 创建新城镇：延迟检查城镇全部地块中的上锁箱子。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNewTown(NewTownEvent event) {
        Town town = event.getTown();
        if (town == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (TownBlock townBlock : town.getTownBlocks()) {
                    scanTownBlockForLockedChests(townBlock);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Towny town creation auto-unlock check failed: " + e.getMessage());
            }
        });
    }

    /** 新地块加入城镇：延迟检查该地块中的上锁箱子。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTownClaim(TownClaimEvent event) {
        TownBlock townBlock = event.getTownBlock();
        if (townBlock == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                scanTownBlockForLockedChests(townBlock);
            } catch (Exception e) {
                plugin.getLogger().fine("Towny town claim auto-unlock check failed: " + e.getMessage());
            }
        });
    }

    /**
     * 扫描单个城镇地块（16x16 区块）中的箱子，对已上锁的箱子执行自动卸锁回调。
     * 仅检查已加载的区块（未加载的区块说明无活跃玩家，其内的箱子暂不处理）。
     */
    private void scanTownBlockForLockedChests(TownBlock townBlock) {
        World world = townBlock.getWorldCoord().getBukkitWorld();
        if (world == null) {
            world = Bukkit.getWorld(townBlock.getWorldCoord().getWorldName());
        }
        if (world == null) return;
        int cx = townBlock.getX();
        int cz = townBlock.getZ();
        if (!world.isChunkLoaded(cx, cz)) return;
        Chunk chunk = world.getChunkAt(cx, cz);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (isChest(block)) {
                        // protectionCreatedHandler 内部会通过 chestService.isLocked() 判断，
                        // 仅对上锁的箱子执行自动卸锁
                        protectionCreatedHandler.accept(block);
                    }
                }
            }
        }
    }

    /** 判断方块是否为箱子（普通箱子或陷阱箱）。 */
    private boolean isChest(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }
}
