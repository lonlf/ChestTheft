package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.item.CrossPluginItemUtil;
import com.lonleaf.chesttheft.item.ItemDefinition;
import com.lonleaf.chesttheft.item.ItemManager;
import com.lonleaf.chesttheft.item.ItemType;
import com.lonleaf.chesttheft.model.BlockLocation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

public class CheckCommand implements Command {
    private final ItemManager itemManager;

    public CheckCommand(ItemManager itemManager) {
        this.itemManager = itemManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("check")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, Messages.PLAYER_ONLY, Messages.PLAYER_ONLY_FORMAT);
            return true;
        }
        // chesttheft.check（默认 op）或 chesttheft.admin 任一即可；default:false 连 OP 也不授予
        if (!player.hasPermission("chesttheft.check") && !player.hasPermission("chesttheft.admin")) {
            Messages.send(player, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            Messages.send(player, Messages.SETITEM_NO_ITEM, Messages.SETITEM_NO_ITEM_FORMAT);
            return true;
        }
        ItemType type = itemManager.getType(hand);
        if (type == null) {
            // 区分"无 PDC 标签"与"有标签但定义缺失（items/*.yml 未加载或 ID 不匹配）"
            String rawId = itemManager.getId(hand);
            if (rawId == null) {
                Messages.send(player, Messages.CHECK_NOT_SPECIAL, Messages.CHECK_NOT_SPECIAL_FORMAT);
            } else {
                Messages.send(player, Messages.CHECK_PDC, Messages.CHECK_PDC_FORMAT,
                        rawId, Messages.CHECK_TYPE_NOT_DEFINED);
            }
        } else {
            Messages.send(player, Messages.CHECK_PDC, Messages.CHECK_PDC_FORMAT,
                    itemManager.getId(hand),
                    type.name().toLowerCase(Locale.ROOT));
            // 底座诊断：显示物品本体来源（未配置 / 已解析 / 解析失败回退）
            ItemDefinition def = itemManager.getDefinition(itemManager.getId(hand));
            if (def != null) {
                String externalId = def.getExternalId();
                if (externalId == null) {
                    Messages.send(player, Messages.CHECK_BASE_NONE, Messages.CHECK_PDC_FORMAT, def.getMaterial());
                } else if (CrossPluginItemUtil.getItem(externalId, player) != null) {
                    Messages.send(player, Messages.CHECK_BASE_RESOLVED, Messages.CHECK_PDC_FORMAT, externalId);
                } else {
                    Messages.send(player, Messages.CHECK_BASE_FAILED, Messages.CHECK_PDC_FORMAT,
                            externalId, def.getMaterial());
                }
            }
            // 只有钥匙需要显示配对锁与锁凭证
            if (type == ItemType.KEY) {
                BlockLocation paired = itemManager.getPairedLock(hand);
                String token = itemManager.getPairedToken(hand);
                Messages.send(player, Messages.CHECK_KEY_INFO, Messages.CHECK_KEY_INFO_FORMAT,
                        paired == null ? Messages.NONE : paired.toString(),
                        token == null ? Messages.NONE : token);
            }
        }
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        return List.of();
    }
}
