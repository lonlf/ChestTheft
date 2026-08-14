package com.lonleaf.chesttheft.command.commands;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.stream.Collectors;

public interface Command {
    boolean execute(CommandSender sender, String[] args);

    List<String> completeList(String[] args);

    // 辅助方法：过滤补全列表
    default List<String> filter(List<String> list, String startsWith) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(startsWith.toLowerCase()))
                .collect(Collectors.toList());
    }
}
