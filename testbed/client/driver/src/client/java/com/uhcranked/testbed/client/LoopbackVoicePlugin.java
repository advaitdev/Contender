package com.uhcranked.testbed.client;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;

/** Optional real voice client fixture. All UDP traffic stays on loopback. */
public final class LoopbackVoicePlugin implements VoicechatPlugin {
    @Override public String getPluginId() { return "uhcr-testbed-loopback"; }

    @Override public void registerEvents(EventRegistration events) {
        if (!Boolean.getBoolean("uhcr.testbed") || !"1".equals(System.getenv("UHCR_TESTBED_VOICE_MODERATION"))) {
            throw new IllegalStateException("Voice fixture requires an owned voice testbed run");
        }
        events.registerEvent(ClientVoicechatInitializationEvent.class,
                event -> event.setSocketImplementation(new LoopbackSocket()));
    }

    private static final class LoopbackSocket implements ClientVoicechatSocket {
        private volatile DatagramSocket socket;

        @Override public void open() throws Exception {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        }

        @Override public RawUdpPacket read() throws Exception {
            var packet = new DatagramPacket(new byte[4096], 4096);
            socket.receive(packet);
            if (!packet.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Non-loopback voice peer");
            return new Packet(Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                    packet.getOffset() + packet.getLength()), System.currentTimeMillis(), packet.getSocketAddress());
        }

        @Override public void send(byte[] data, SocketAddress address) throws Exception {
            if (!(address instanceof InetSocketAddress target) || target.getAddress() == null
                    || !target.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Non-loopback voice peer");
            socket.send(new DatagramPacket(data, data.length, address));
        }

        @Override public void close() { if (socket != null) socket.close(); }
        @Override public boolean isClosed() { return socket == null || socket.isClosed(); }
    }

    private record Packet(byte[] data, long timestamp, SocketAddress address) implements RawUdpPacket {
        @Override public byte[] getData() { return data; }
        @Override public long getTimestamp() { return timestamp; }
        @Override public SocketAddress getSocketAddress() { return address; }
    }
}
