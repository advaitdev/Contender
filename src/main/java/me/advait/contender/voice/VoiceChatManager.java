package me.advait.contender.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import me.advait.contender.Contender;

/** Routes spectator microphones privately and filters every outgoing voice packet type. */
public final class VoiceChatManager implements VoicechatPlugin {
    private final VoiceRouting routing;
    VoiceChatManager(VoiceRouting routing) { this.routing = routing; }
    public static void setup(Contender plugin) {
        var provider = plugin.getServer().getServicesManager().getRegistration(BukkitVoicechatService.class);
        if (provider == null) {
            plugin.getLogger().warning("Simple Voice Chat's API is unavailable; voice controls are disabled.");
            return;
        }
        provider.getProvider().registerPlugin(new VoiceChatManager(plugin.getVoiceRouting()));
    }
    @Override public String getPluginId() { return "contender"; }
    @Override public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onSound);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onSound);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onSound);
    }
    void onMicrophone(MicrophonePacketEvent event) {
        if (event.isCancelled() || event.getSenderConnection() == null) return;
        var speakerId = event.getSenderConnection().getPlayer().getUuid();
        var members = routing.snapshot();
        var speaker = members.get(speakerId);
        if (speaker == null || speaker.muted()) { event.cancel(); return; }
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
        if (event.isCancelled() || event.getReceiverConnection() == null) return;
        var members = routing.snapshot();
        var receiver = members.get(event.getReceiverConnection().getPlayer().getUuid());
        if (receiver == null || receiver.deafened()) { event.cancel(); return; }
        var senderId = event.getSenderConnection() == null ? event.getPacket().getSender()
                : event.getSenderConnection().getPlayer().getUuid();
        var sender = senderId == null ? null : members.get(senderId);
        boolean forwarded = senderId != null && VoiceRouting.channelId(senderId).equals(event.getPacket().getChannelId());
        if (forwarded) {
            if (!VoiceRouting.hearsSpectatorChannel(sender, receiver)) event.cancel();
            return;
        }
        if (sender == null) {
            // Preserve unrelated plugin audio; reject late player packets after disconnect.
            if (!SoundPacketEvent.SOURCE_PLUGIN.equals(event.getSource())) event.cancel();
            return;
        }
        if (sender.muted() || sender.spectator() || receiver.spectator()) {
            // Privacy applies to admins too. Forwarded copies replace the spectator's normal audio.
            event.cancel();
        }
    }
}
