package com.lonleaf.chesttheft.protection;

import com.lonleaf.chesttheft.database.Database;
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
 * NoBuildPlus 保护集成（软依赖）：世界级 flag 保护，打开前临时授予世界 bypass 权限。
 * 权限附件不持久化（重启即清）。
 */
public class NoBuildPlusProtectionListener extends TempAccessListener {

    public NoBuildPlusProtectionListener(Plugin plugin, Database database) {
        super(plugin, database);
    }

    @Override
    protected String pluginType() {
        return "nobuildplus";
    }

    @Override
    protected String serializeGrant(TempGrant grant) {
        return "";
    }

    @Override
    protected void revokeFromRecord(Block block, UUID playerUuid, String extra) {
        // 权限附件随服务器重启自动清空，无需恢复
    }

    @Override
    protected TempGrant prepare(Block block, Player player) {
        if (Bukkit.getPluginManager().getPlugin("NoBuildPlus") == null) {
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
