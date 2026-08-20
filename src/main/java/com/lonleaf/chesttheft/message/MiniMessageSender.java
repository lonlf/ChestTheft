package com.lonleaf.chesttheft.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import org.bukkit.entity.Player;

/**
 * 基于 PacketEvents 的消息输出通道：直接以 ChatComponent JSON 发送 title / actionbar。
 *
 * <p>JSON 由 {@link ChatJson} 构建（可携带 font 字段），经协议包发送，保留字体信息，
 * 是位图字体渲染的前提。以 JSON 字符串发包而非组件对象，不依赖任何运行时文本库版本。
 */
public final class MiniMessageSender {

    private MiniMessageSender() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 发送标题（标题 + 副标题 + 显示时长）。
     *
     * @param titleJson    主标题 ChatComponent JSON，可为 null 表示不更新
     * @param subtitleJson 副标题 ChatComponent JSON，可为 null 表示不更新
     */
    public static void sendTitle(Player player, String titleJson, String subtitleJson, int fadeIn, int stay, int fadeOut) {
        var pm = PacketEvents.getAPI().getPlayerManager();
        if (titleJson != null) {
            pm.sendPacket(player, new WrapperPlayServerSetTitleText(titleJson));
        }
        if (subtitleJson != null) {
            pm.sendPacket(player, new WrapperPlayServerSetTitleSubtitle(subtitleJson));
        }
        pm.sendPacket(player, new WrapperPlayServerSetTitleTimes(fadeIn, stay, fadeOut));
    }

    /** 发送动作栏消息（overlay 系统聊天消息），内容为 ChatComponent JSON。 */
    public static void sendActionBar(Player player, String json) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerSystemChatMessage(true, json));
    }
}
