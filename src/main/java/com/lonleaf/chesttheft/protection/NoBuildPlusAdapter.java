package com.lonleaf.chesttheft.protection;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import p1xel.nobuildplus.Flags;
import p1xel.nobuildplus.NoBuildPlus;
import p1xel.nobuildplus.world.ProtectedWorld;
import p1xel.nobuildplus.world.WorldManager;

import java.util.UUID;

/**
 * NoBuildPlus 保护适配器（软依赖）：世界级 flag 保护，打开前事件内临时授予世界 bypass 权限。
 * 权限附件为纯内存态（重启即清），不写库不持久化。
 */
public class NoBuildPlusAdapter extends TempAccessAdapter {

    public NoBuildPlusAdapter(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String pluginType() {
        return "nobuildplus";
    }

    @Override
    public boolean isActive() {
        return Bukkit.getPluginManager().getPlugin("NoBuildPlus") != null;
    }

    @Override
    public boolean isProtected(Block block) {
        return ProtectionUtil.isNoBuildPlusProtected(block);
    }

    @Override
    public UUID getOwnerUUID(Block block) {
        return null; // 世界级 flag 保护无所有者概念
    }

    /** 世界 bypass 权限为纯内存附件（重启即清），不落库。 */
    @Override
    protected boolean persistGrant(TempGrant grant) {
        return false;
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        return "";
    }

    @Override
    protected boolean revokeFromRecord(Block block, UUID playerUuid, String extra) {
        // 附件型不落库，无记录可恢复（实际不会被调用，仅满足签名）
        return true;
    }

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (!isActive()) {
            return null;
        }
        String worldName = block.getWorld().getName();
        p1xel.nobuildplus.api.NBPAPI api = NoBuildPlus.getInstance().getAPI();
        if (api == null || !api.isWorldEnabled(worldName)) {
            return null;
        }
        // 仅当世界启用了会拦截容器打开的 flag 时才需要授予 bypass 权限
        if (!api.canExecute(worldName, Flags.container) && !api.canExecute(worldName, Flags.use)) {
            return null;
        }
        ProtectedWorld world = WorldManager.getWorld(worldName);
        if (world == null) {
            return null;
        }
        String permission = world.getPermission();
        if (permission == null || permission.isEmpty() || player.hasPermission(permission)) {
            return null;
        }
        return new NbpGrant(permission);
    }

    @Override
    protected void apply(Block block, Player player, TempGrant grant) {
        NbpGrant nbpGrant = (NbpGrant) grant;
        nbpGrant.attachment = player.addAttachment(plugin, nbpGrant.permission, true);
    }

    @Override
    protected void revoke(Block block, Player player, TempGrant grant) {
        if (grant.granted && ((NbpGrant) grant).attachment != null) {
            ((NbpGrant) grant).attachment.remove();
        }
    }

    /** NoBuildPlus 临时授权记录：携带世界 bypass 权限与已添加的权限附件（移除附件即撤销权限）。 */
    private static final class NbpGrant extends TempGrant {
        final String permission;
        /** apply 阶段创建；撤销时移除。 */
        PermissionAttachment attachment;

        NbpGrant(String permission) {
            super(true);
            this.permission = permission;
        }
    }
}
