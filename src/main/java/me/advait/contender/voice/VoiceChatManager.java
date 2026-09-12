package me.advait.contender.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import me.advait.contender.Contender;

/** Routes spectator microphones privately and filters every outgoing voice packet type. */
public final class VoiceChatManager implements VoicechatPlugin {
    private final VoiceRouting routing;
    private volatile VoicechatServerApi api;
    VoiceChatManager(VoiceRouting routing) { this.routing = routing; }
    public static void setup(Contender plugin) {
        var provider = plugin.getServer().getServicesManager().getRegistration(BukkitVoicechatService.class);
        if (provider == null) {
            plugin.getVoiceDiagnostics().integration("API unavailable");
            plugin.getLogger().warning("Simple Voice Chat's API is unavailable; voice controls are disabled.");
            return;
        }
        plugin.getVoiceDiagnostics().integration("Waiting for Simple Voice Chat");
        provider.getProvider().registerPlugin(new VoiceChatManager(plugin.getVoiceRouting()));
    }
    @Override public String getPluginId() { return "contender"; }
    @Override public void registerEvents(EventRegistration registration) {
        routing.diagnostics().integration("Registered; waiting for the voice server");
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> serverReady(event.getVoicechat()));
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            if (!routing.isActive()) return;
            api = null;
            routing.diagnostics().integration("Voice server stopped");
            routing.diagnostics().connection(id -> "Not available");
        });
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onSound);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onSound);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onSound);
    }
    private void serverReady(VoicechatServerApi api) {
        if (!routing.isActive() || api == null || this.api == api) return;
        this.api = api;
        routing.diagnostics().integration("Active");
        routing.diagnostics().connection(id -> {
            var connection = api.getConnectionOf(id);
            if (connection == null) return "No voice connection";
            if (!connection.isInstalled()) return "Client voice mod not detected";
            if (!connection.isConnected()) return "Disconnected";
            String status = connection.isDisabled() ? "Connected; voice disabled on client" : "Connected; voice enabled";
            var group = connection.getGroup();
            return group == null ? status + "; no group" : status + "; group: " + group.getName();
        });
    }
    void onMicrophone(MicrophonePacketEvent event) {
        if (!routing.isActive() || event.getSenderConnection() == null) return;
        serverReady(event.getVoicechat());
        var speakerId = event.getSenderConnection().getPlayer().getUuid();
        routing.diagnostics().microphone(speakerId, event.isCancelled());
        if (event.isCancelled()) return;
        var members = routing.snapshot();
        var speaker = members.get(speakerId);
        if (speaker == null || speaker.muted()) {
            routing.diagnostics().blockedMicrophone(speakerId, speaker == null ? "Speaker is missing from routing" : "Speaking is muted");
            event.cancel(); return;
        }
        // Spectators never enter proximity or user-created group channels.
        if (speaker.spectator()) event.cancel();
        var packet = event.getPacket().staticSoundPacketBuilder().channelId(speaker.channel()).build();
        for (var entry : members.entrySet()) {
            if (entry.getKey().equals(speakerId) || !VoiceRouting.hearsSpectatorChannel(speaker, entry.getValue())) continue;
            var receiver = event.getVoicechat().getConnectionOf(entry.getKey());
            if (receiver != null) event.getVoicechat().sendStaticSoundPacketTo(receiver, packet);
        }
    }
    void onSound(SoundPacketEvent<? extends SoundPacket> event) {
        if (!routing.isActive() || event.getReceiverConnection() == null) return;
        serverReady(event.getVoicechat());
        var receiverId = event.getReceiverConnection().getPlayer().getUuid();
        if (event.isCancelled()) { routing.diagnostics().outgoing(receiverId, null, true); return; }
        String reason = blockReason(event);
        routing.diagnostics().outgoing(receiverId, reason, false);
        if (reason != null) event.cancel();
    }
    private String blockReason(SoundPacketEvent<? extends SoundPacket> event) {
        var members = routing.snapshot();
        var receiver = members.get(event.getReceiverConnection().getPlayer().getUuid());
        if (receiver == null) return "Listener is missing from routing";
        if (receiver.deafened()) return "Hearing is deafened";
        var senderId = event.getSenderConnection() == null ? event.getPacket().getSender()
                : event.getSenderConnection().getPlayer().getUuid();
        var sender = senderId == null ? null : members.get(senderId);
        boolean forwarded = senderId != null && VoiceRouting.channelId(senderId).equals(event.getPacket().getChannelId());
        if (forwarded) {
            return VoiceRouting.hearsSpectatorChannel(sender, receiver) ? null : "Private spectator channel";
        }
        if (sender == null) {
            // Preserve unrelated plugin audio; reject late player packets after disconnect.
            return SoundPacketEvent.SOURCE_PLUGIN.equals(event.getSource()) ? null : "Speaker left the server";
        }
        if (sender.muted() || sender.spectator() || receiver.spectator()) {
            // Privacy applies to admins too. Forwarded copies replace the spectator's normal audio.
            return sender.muted() ? "Speaker is muted" : "Spectator audio uses the private channel";
        }
        return null;
    }
}
