package me.advait.contender.sabotage;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import io.netty.util.ReferenceCountUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Adds real latency to a player's connection by holding packets in both directions.
 * The player's ping in the tab list rises by the same amount, as if their connection got worse.
 */
public final class LagInjector {
    private static final String HANDLER = "contender_lag";
    private final Logger logger;
    private Method handle;
    private Field gameListener, connection, channel;
    private boolean available;

    public LagInjector(Logger logger) {
        this.logger = logger;
        try {
            Class<?> craftPlayer = Class.forName(org.bukkit.Bukkit.getServer().getClass().getPackageName() + ".entity.CraftPlayer");
            handle = craftPlayer.getMethod("getHandle");
            gameListener = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
            connection = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl").getField("connection");
            channel = Class.forName("net.minecraft.network.Connection").getField("channel");
            available = true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            logger.warning("The Lag Spike sabotage is unavailable on this server version: " + failure);
        }
    }

    public boolean available() { return available; }

    private Channel channel(Player player) throws ReflectiveOperationException {
        Object serverPlayer = handle.invoke(player);
        Object listener = gameListener.get(serverPlayer);
        Object network = connection.get(listener);
        return (Channel) channel.get(network);
    }

    /** Adds {@code roundTripMillis} of ping. Safe to call repeatedly. */
    public void add(Player player, int roundTripMillis) {
        if (!available) return;
        try {
            Channel target = channel(player);
            long oneWay = Math.max(1, roundTripMillis / 2);
            target.eventLoop().execute(() -> {
                var pipeline = target.pipeline();
                if (pipeline.get(HANDLER) != null || pipeline.get("packet_handler") == null) return;
                pipeline.addBefore("packet_handler", HANDLER, new Delay(oneWay));
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logger.fine("Could not add lag for " + player.getName() + ": " + failure);
        }
    }

    public void remove(Player player) {
        if (!available) return;
        try {
            Channel target = channel(player);
            target.eventLoop().execute(() -> {
                if (target.pipeline().get(HANDLER) != null) target.pipeline().remove(HANDLER);
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logger.fine("Could not remove lag for " + player.getName() + ": " + failure);
        }
    }

    /**
     * Delays every packet by the same amount, so their order never changes. Packets still held when the
     * handler is removed are released at once, before anything sent afterwards.
     */
    static final class Delay extends ChannelDuplexHandler {
        private record Write(Object message, ChannelPromise promise) { }
        private final long millis;
        // Only touched on the channel's event loop.
        private final ArrayDeque<Object> reads = new ArrayDeque<>();
        private final ArrayDeque<Write> writes = new ArrayDeque<>();

        Delay(long millis) { this.millis = millis; }

        @Override public void channelRead(ChannelHandlerContext context, Object message) {
            reads.add(message);
            context.executor().schedule(() -> {
                Object next = reads.poll();
                if (next != null) context.fireChannelRead(next);
            }, millis, TimeUnit.MILLISECONDS);
        }

        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            writes.add(new Write(message, promise));
            context.executor().schedule(() -> {
                Write next = writes.poll();
                if (next != null) context.writeAndFlush(next.message(), next.promise());
            }, millis, TimeUnit.MILLISECONDS);
        }

        @Override public void handlerRemoved(ChannelHandlerContext context) {
            boolean open = context.channel().isActive();
            for (Object read; (read = reads.poll()) != null; ) {
                if (open) context.fireChannelRead(read); else ReferenceCountUtil.release(read);
            }
            boolean wrote = false;
            for (Write write; (write = writes.poll()) != null; ) {
                if (open) { context.write(write.message(), write.promise()); wrote = true; }
                else { ReferenceCountUtil.release(write.message()); write.promise().tryFailure(new ClosedChannelException()); }
            }
            if (wrote) context.flush();
        }
    }
}
