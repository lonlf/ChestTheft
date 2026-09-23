package com.lonleaf.chesttheft.command.commands;

import com.lonleaf.chesttheft.config.GameConfig;
import com.lonleaf.chesttheft.config.LockConfigManager;
import com.lonleaf.chesttheft.config.Messages;
import com.lonleaf.chesttheft.config.SoundConfig;
import com.lonleaf.chesttheft.minigame.GameManager;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Stream;

/**
 * 调试命令（debug）：
 * - minigame [level]：以指定等级配置直接开始一局小游戏，便于测试玩法；
 * - sound <sound-key> [level]：按指定等级（默认 0）的 sounds 配置播放一次音效，便于调音测试。
 */
public class DebugCommand implements Command {

    /** 可测试的音效键：对应 config.yml game.sounds 小节（等级配置可覆写单个节点）。 */
    private static final List<String> SOUND_KEYS =
            List.of("success", "fail", "move", "hit", "a-move", "a-unlock", "b-move");

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
        if (args.length < 2) {
            Messages.send(sender, Messages.USAGE, Messages.USAGE_FORMAT);
            return true;
        }
        if (args[1].equalsIgnoreCase("minigame")) {
            return runMinigame(player, args);
        }
        if (args[1].equalsIgnoreCase("sound")) {
            return runSound(player, args);
        }
        Messages.send(sender, Messages.USAGE, Messages.USAGE_FORMAT);
        return true;
    }

    /** debug minigame [level]：以指定等级配置开始一局小游戏；不指定等级时为 0（默认配置）。 */
    private boolean runMinigame(Player player, String[] args) {
        int level = 0;
        if (args.length >= 3) {
            try {
                level = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                Messages.send(player, Messages.DEBUG_LEVEL_INVALID, Messages.DEBUG_LEVEL_INVALID_FORMAT, args[2]);
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
        Messages.send(player, Messages.DEBUG_GAME_START, Messages.DEBUG_GAME_START_FORMAT,
                config.getGameType(), level);
        return true;
    }

    /** debug sound <sound-key> [level]：按指定等级合并后的 sounds 配置播放一次音效（未配置则提示）。 */
    private boolean runSound(Player player, String[] args) {
        if (args.length < 3) {
            Messages.send(player, Messages.USAGE, Messages.USAGE_FORMAT);
            return true;
        }
        String rawKey = args[2];
        String key = SOUND_KEYS.stream()
                .filter(k -> k.equalsIgnoreCase(rawKey))
                .findFirst()
                .orElse(null);
        if (key == null) {
            Messages.send(player, Messages.DEBUG_SOUND_KEY_INVALID, Messages.DEBUG_SOUND_KEY_INVALID_FORMAT, rawKey);
            return true;
        }
        int level = 0;
        if (args.length >= 4) {
            try {
                level = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                Messages.send(player, Messages.DEBUG_LEVEL_INVALID, Messages.DEBUG_LEVEL_INVALID_FORMAT, args[3]);
                return true;
            }
        }
        GameConfig config = lockConfigManager.getGameConfig(level);
        ConfigurationSection sounds = config.getSection() == null ? null
                : config.getSection().getConfigurationSection("sounds");
        SoundConfig sound = SoundConfig.from(sounds == null ? null : sounds.getConfigurationSection(key));
        if (!sound.isEnabled()) {
            Messages.send(player, Messages.DEBUG_SOUND_DISABLED, Messages.DEBUG_SOUND_DISABLED_FORMAT, key, level);
            return true;
        }
        sound.play(player);
        Messages.send(player, Messages.DEBUG_SOUND_PLAY, Messages.DEBUG_SOUND_PLAY_FORMAT, key, level);
        return true;
    }

    @Override
    public List<String> completeList(String[] args) {
        if (args.length == 2) {
            return filter(List.of("minigame", "sound"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("minigame")) {
            // 0 表示默认配置（不指定等级），其余为已配置的等级；等级 0 已在配置中时去重
            return filter(levelCandidates(), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("sound")) {
            return filter(SOUND_KEYS, args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("sound")) {
            return filter(levelCandidates(), args[3]);
        }
        return List.of();
    }

    /** 等级候选：0（默认配置）+ 全部已配置等级（升序，去重）。 */
    private List<String> levelCandidates() {
        return Stream.concat(Stream.of("0"),
                        lockConfigManager.getLevels().stream().map(String::valueOf))
                .distinct()
                .toList();
    }
}
