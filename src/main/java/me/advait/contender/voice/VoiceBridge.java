package me.advait.contender.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import me.advait.contender.Contender;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** The only class that references Simple Voice Chat. Loaded when the plugin is present. */
final class VoiceBridge implements VoicechatPlugin {
    private final Contender plugin;
    private final VoiceService service;
    private volatile VoicechatServerApi api;
    private volatile boolean failed;

    private VoiceBridge(Contender plugin, VoiceService service) {
        this.plugin = plugin;
        this.service = service;
    }

    static Object register(Contender plugin, VoiceService service) {
        var registration = plugin.getServer().getServicesManager().getRegistration(BukkitVoicechatService.class);
        if (registration == null) throw new IllegalStateException("Simple Voice Chat's service is not registered.");
        VoiceBridge bridge = new VoiceBridge(plugin, service);
        registration.getProvider().registerPlugin(bridge);
        return bridge;
    }

    static String status(Object bridge, UUID player) {
        VoicechatServerApi api = ((VoiceBridge) bridge).api;
        if (api == null) return "Voice server: waiting to start.";
        var connection = api.getConnectionOf(player);
        if (connection == null) return "Voice connection: none (the voice mod may be missing).";
        if (!connection.isInstalled()) return "Voice connection: client mod not detected.";
        if (!connection.isConnected()) return "Voice connection: disconnected.";
        return "Voice connection: connected" + (connection.isDisabled() ? ", but voice is turned off in the client." : ".")
                + (connection.getGroup() == null ? "" : " Group: " + connection.getGroup().getName() + ".");
    }

    @Override public String getPluginId() { return "contender"; }

    @Override public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> api = event.getVoicechat());
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> api = null);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    /** Directors' microphones also reach everyone outside their normal hearing range. Never cancels audio. */
    private void onMicrophone(MicrophonePacketEvent event) {
        if (failed || event.isCancelled() || event.getSenderConnection() == null) return;
        try {
            Set<UUID> broadcasters = service.broadcasters();
            if (broadcasters.isEmpty()) return;
            var sender = event.getSenderConnection().getPlayer();
            if (!broadcasters.contains(sender.getUuid())) return;
            VoicechatServerApi server = event.getVoicechat();
            double range = server.getVoiceChatDistance();
            var packet = event.getPacket().staticSoundPacketBuilder().build();
            var senderPosition = sender.getPosition();
            Object senderLevel = sender.getServerLevel().getServerLevel();
            for (UUID id : service.online()) {
                if (id.equals(sender.getUuid())) continue;
                var receiver = server.getConnectionOf(id);
                if (receiver == null || !receiver.isConnected()) continue;
                var listener = receiver.getPlayer();
                var position = listener.getPosition();
                double dx = position.getX() - senderPosition.getX(), dy = position.getY() - senderPosition.getY(), dz = position.getZ() - senderPosition.getZ();
                boolean nearby = listener.getServerLevel().getServerLevel() == senderLevel && dx * dx + dy * dy + dz * dz <= range * range;
                if (!nearby) server.sendStaticSoundPacketTo(receiver, packet);
            }
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            plugin.getLogger().log(Level.WARNING, "Director voice broadcast stopped after an error; normal voice chat is unaffected.", failure);
        }
    }
}
