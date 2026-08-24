package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.minigame.GameManager;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 调试命令（debug）：子命令 minigame [level] 以指定等级配置直接开始一局小游戏，便于测试玩法。
 */
public class DebugCommand implements Command {

    private final GameManager gameManager;
    private final LockConfigManager lockConfigManager;

    public DebugCommand(GameManager gameManager, LockConfigManager lockConfigManager) {
        this.gameManager = gameManager;
        this.lockConfigManager = lockConfigManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("debug")) {
            return false;
        }
        if (!sender.hasPermission("chesttheft.admin")) {
            Messages.send(sender, Messages.NO_PERMISSION, Messages.NO_PERMISSION_FORMAT);
            return true;
        }
        if (!(sender instanceof Player player)) {
            Messages.send(sender, Messages.PLAYER_ONLY, Messages.PLAYER_ONLY_FORMAT);
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("minigame")) {
            Messages.send(sender, Messages.USAGE, Messages.USAGE_FORMAT);
            return true;
        }
        int level = 0;
        if (args.length >= 3) {
            try {
                level = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                Messages.send(sender, Messages.DEBUG_LEVEL_INVALID, Messages.DEBUG_LEVEL_INVALID_FORMAT, args[2]);
                return true;
            }
        }
        GameConfig config = lockConfigManager.getGameConfig(level);
        // 目标箱子取玩家视线指向的方块，未指向时用脚下方块（保证非空，避免触发器解析位置失败）
        Block target = player.getTargetBlockExact(5);
        if (target == null) {
            target = player.getLocation().subtract(0, 1, 0).getBlock();
        }
        if (!gameManager.startGame(player, target, config)) {
            return true;
        }
        Messages.send(sender, Messages.DEBUG_GAME_START, Messages.DEBUG_GAME_START_FORMAT,
                config.getGameType(), level);
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return filter(List.of("minigame"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("minigame")) {
            // 0 表示默认配置（不指定等级），其余为已配置的等级；等级 0 已在配置中时去重
            return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of("0"),
                            lockConfigManager.getLevels().stream().map(String::valueOf))
                    .distinct()
                    .filter(s -> s.startsWith(args[2]))
                    .toList();
        }
        return List.of();
    }
}
