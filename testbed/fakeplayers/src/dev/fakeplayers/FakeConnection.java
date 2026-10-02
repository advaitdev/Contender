package dev.fakeplayers;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.bukkit.Bukkit;

import java.net.InetSocketAddress;

/**
 * A connection with no client behind it. Outgoing packets go to the bot (so it can reply like a client and
 * record chat) and are then dropped. They can also be encoded the way the real pipeline would, to catch
 * packets a real client would be kicked for.
 */
public class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress("127.0.0.1", 0);
        this.preparing = false;
    }

    /** Encode every packet like the real pipeline (/bots verify on|off). Catches broken packets; costs some CPU. */
    static volatile boolean verifyPackets = true;
    private static volatile net.minecraft.network.ProtocolInfo<net.minecraft.network.protocol.game.ClientGamePacketListener> game;
    static final java.util.concurrent.atomic.AtomicLong encoded = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();

    /** Encodes like the real pipeline would, so broken packets (dialogs, components, metadata) show up in the log. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void verify(Packet<?> packet) {
        if (game == null) {
            var access = net.minecraft.server.MinecraftServer.getServer().registryAccess();
            game = net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(net.minecraft.network.RegistryFriendlyByteBuf.decorator(access));
        }
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            for (Object sub : bundle.subPackets()) verify((Packet<?>) sub);
            return;
        }
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        try {
            ((net.minecraft.network.codec.StreamCodec) game.codec()).encode(buffer, packet);
            encoded.incrementAndGet();
        } catch (Throwable failure) {
            failures.incrementAndGet();
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "[FakePlayers] ENCODE FAIL " + packet.getClass().getSimpleName() + ": " + failure, failure);
        } finally {
            buffer.release();
        }
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (this.listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game) {
            if (verifyPackets) verify(packet);
            if (game.player instanceof Bot bot) {
                if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
                    for (Packet<?> inner : bundle.subPackets()) bot.onPacket(inner);
                } else {
                    bot.onPacket(packet);
                }
            }
        }
        if (listener != null) {
            // Pretend the packet was sent so follow-up actions (eg. disconnecting after the kick packet) still happen
            try {
                listener.operationComplete(this.channel.newSucceededFuture());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void flushChannel() {
    }

    private PacketListener listener;

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
        // Skip the netty pipeline setup, but remember the listener so it hears about the disconnect
        this.listener = packetListener;
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        boolean wasConnected = this.isConnected();
        super.disconnect(details);
        if (wasConnected) {
            // Real connections are cleaned up by the network ticker, which doesn't know about us
            FakePlayersPlugin plugin = FakePlayersPlugin.instance;
            if (!plugin.disabling) {
                Bukkit.getScheduler().runTask(plugin, () -> this.notifyListener(details));
            } else {
                this.notifyListener(details);
            }
        }
    }

    private void notifyListener(DisconnectionDetails details) {
        if (this.listener != null) {
            PacketListener listener = this.listener;
            this.listener = null; // only once
            listener.onDisconnect(details); // removes the player from the world and player list
        }
    }
}
