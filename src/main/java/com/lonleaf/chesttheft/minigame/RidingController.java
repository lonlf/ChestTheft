package com.lonleaf.chesttheft.minigame;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import me.tofaa.entitylib.meta.types.LivingEntityMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import org.bukkit.entity.Player;

import java.util.function.IntConsumer;

/**
 * 骑乘游标控制器：发包生成仅对玩家可见的隐形坐骑，使玩家进入骑乘状态（锁定原地），
 * 监听骑乘输入包把 A/D 键转换为游标方向信号（-1 左移 / 0 无输入 / 1 右移）。
 */
public final class RidingController implements PacketListener {

    private final Player player;
    /** 方向回调（netty 线程调用）：-1 左 / 0 无 / 1 右，每次输入包触发一次。 */
    private final IntConsumer onDirection;
    /** 前进（W 键）状态回调（netty 线程调用）：true=按下 / false=松开，可为 null 忽略。 */
    private final java.util.function.Consumer<Boolean> onForward;
    private WrapperEntity mount;
    /** 已注册的输入包监听句柄，stop 时按此注销。 */
    private PacketListenerCommon listener;
    private volatile boolean active;

    public RidingController(Player player, IntConsumer onDirection) {
        this(player, onDirection, null);
    }

    public RidingController(Player player, IntConsumer onDirection, java.util.function.Consumer<Boolean> onForward) {
        this.player = player;
        this.onDirection = onDirection;
        this.onForward = onForward;
    }

    /** 进入骑乘状态：生成隐形坐骑并上马，注册输入包监听。 */
    public void start() {
        if (active) {
            return;
        }
        active = true;
        org.bukkit.Location loc = player.getLocation();
        mount = new WrapperEntity(EntityTypes.BAT);
        mount.consumeEntityMeta(LivingEntityMeta.class, meta -> {
            meta.setInvisible(true);
            meta.setHasNoGravity(true);
        });
        mount.addViewer(player.getUniqueId());
        mount.spawn(new com.github.retrooper.packetevents.protocol.world.Location(
                loc.getX(), loc.getY(), loc.getZ(), 0f, 0f));
        // 上马：客户端随即进入骑乘状态，此后每 tick 发送输入包（A/D 为左右标志）
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerSetPassengers(mount.getEntityId(), new int[]{player.getEntityId()}));
        listener = PacketEvents.getAPI().getEventManager()
                .registerListener(this, PacketListenerPriority.NORMAL);
    }

    /** 退出骑乘状态：注销输入包监听并销毁坐骑（客户端收到销毁包自动下马）。 */
    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        if (mount != null) {
            mount.remove();
            mount = null;
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_INPUT) {
            return;
        }
        Player p = event.getPlayer();
        if (p == null || !p.getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        WrapperPlayClientPlayerInput input = new WrapperPlayClientPlayerInput(event);
        int dir = input.isLeft() ? -1 : input.isRight() ? 1 : 0;
        onDirection.accept(dir);
        if (onForward != null) {
            onForward.accept(input.isForward());
        }
    }
}
