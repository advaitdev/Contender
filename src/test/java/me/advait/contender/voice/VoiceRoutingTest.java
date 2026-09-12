package me.advait.contender.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.packets.*;
import me.advait.contender.chat.ChatSettings;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceRoutingTest {
    @TempDir Path folder;
    private final StateTestServer server = new StateTestServer();
    private final DuelManager duels = mock(DuelManager.class);
    private final RoleManager roles = mock(RoleManager.class);
    private final Duel first = mock(Duel.class), second = mock(Duel.class);
    private final Player alive = player(), dead = player(), director = player(), other = player();
    private ChatSettings settings;
    private VoiceRouting routing;
    private VoiceChatManager voice;
    private static Player player() { var p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID()); return p; }
    @BeforeEach void setup() {
        when(server.plugin.getDataFolder()).thenReturn(folder.toFile());
        settings = new ChatSettings(server.plugin); when(server.plugin.getChatSettings()).thenReturn(settings);
        when(server.plugin.getDuelManager()).thenReturn(duels); when(server.plugin.getRoleManager()).thenReturn(roles);
        when(roles.isContestant(any())).thenReturn(true); when(roles.isContestant(director.getUniqueId())).thenReturn(false);
        doReturn(List.of(alive, dead, director, other)).when(server.server).getOnlinePlayers();
        when(duels.getDuel(alive.getUniqueId())).thenReturn(first); when(duels.getDuel(dead.getUniqueId())).thenReturn(first);
        when(duels.getDuel(other.getUniqueId())).thenReturn(second); when(first.isSpectator(dead.getUniqueId())).thenReturn(true);
        routing = new VoiceRouting(server.plugin); routing.enable(); voice = new VoiceChatManager(routing);
    }
    @AfterEach void close() { routing.disable(); server.close(); }
    private VoicechatConnection connection(Player player) {
        var connection = mock(VoicechatConnection.class, RETURNS_DEEP_STUBS);
        UUID id = player.getUniqueId(); when(connection.getPlayer().getUuid()).thenReturn(id); return connection;
    }
    @Test void dyingAndRespawningUpdateRoutingWithoutUsingMinecraftGameMode() {
        assertTrue(routing.snapshot().get(dead.getUniqueId()).spectator());
        assertFalse(routing.snapshot().get(alive.getUniqueId()).spectator());
        var old = routing.snapshot();
        when(first.isSpectator(alive.getUniqueId())).thenReturn(true); routing.refresh();
        assertTrue(routing.snapshot().get(alive.getUniqueId()).spectator()); assertFalse(old.get(alive.getUniqueId()).spectator());
        when(first.isSpectator(dead.getUniqueId())).thenReturn(false); routing.refresh();
        assertFalse(routing.snapshot().get(dead.getUniqueId()).spectator());
        verify(dead, never()).getGameMode(); verify(alive, never()).getGameMode();
    }
    @Test void privateChannelIncludesAllSpectatorsAndOnlyTheMatchTheyWatch() {
        var members = routing.snapshot();
        var deadState = members.get(dead.getUniqueId()); var aliveState = members.get(alive.getUniqueId());
        assertTrue(VoiceRouting.hearsSpectatorChannel(members.get(director.getUniqueId()), deadState));
        assertFalse(VoiceRouting.hearsSpectatorChannel(deadState, aliveState));
        assertTrue(VoiceRouting.hearsSpectatorChannel(aliveState, deadState));
        assertFalse(VoiceRouting.hearsSpectatorChannel(members.get(other.getUniqueId()), deadState));
        settings.setSpectatorsHearMatch(false); routing.refresh();
        assertFalse(VoiceRouting.hearsSpectatorChannel(aliveState, routing.snapshot().get(dead.getUniqueId())));
    }
    @Test void muteAndDeafenStillApplyButAdminBypassNeverLeaksSpectatorSpeech() {
        settings.setMuteSpectatorVoiceChat(true); settings.setDeafenSpectatorVoiceChat(true); routing.refresh();
        assertTrue(routing.snapshot().get(dead.getUniqueId()).muted()); assertTrue(routing.snapshot().get(dead.getUniqueId()).deafened());
        when(dead.hasPermission("contender.admin")).thenReturn(true); routing.refresh();
        var admin = routing.snapshot().get(dead.getUniqueId()); assertFalse(admin.muted()); assertFalse(admin.deafened());
        assertFalse(VoiceRouting.hearsSpectatorChannel(admin, routing.snapshot().get(alive.getUniqueId())));
    }
    @Test void spectatorMicrophoneIsCancelledAndForwardedOnlyToOtherSpectators() {
        var event = mock(MicrophonePacketEvent.class, RETURNS_DEEP_STUBS);
        VoicechatConnection sender = connection(dead), receiver = connection(director);
        when(event.getSenderConnection()).thenReturn(sender);
        var builder = mock(StaticSoundPacket.Builder.class, RETURNS_SELF); var packet = mock(StaticSoundPacket.class);
        when(event.getPacket().staticSoundPacketBuilder()).thenReturn(builder); when(builder.build()).thenReturn(packet);
        when(event.getVoicechat().getConnectionOf(director.getUniqueId())).thenReturn(receiver);
        voice.onMicrophone(event);
        verify(event).cancel(); verify(builder).channelId(routing.snapshot().get(dead.getUniqueId()).channel());
        verify(event.getVoicechat()).sendStaticSoundPacketTo(receiver, packet);
        verify(event.getVoicechat(), times(1)).getConnectionOf(any(UUID.class));
        verify(event.getVoicechat(), times(1)).sendStaticSoundPacketTo(any(), any());
    }
    @Test void outgoingEntityLocationAndGroupPacketsCannotLeakToLivingPlayers() {
        for (Class<? extends SoundPacketEvent> type : List.of(EntitySoundPacketEvent.class, LocationalSoundPacketEvent.class, StaticSoundPacketEvent.class)) {
            var event = mock(type, RETURNS_DEEP_STUBS);
            VoicechatConnection sender = connection(dead), receiver = connection(alive);
            when(event.getSenderConnection()).thenReturn(sender); when(event.getReceiverConnection()).thenReturn(receiver);
            when(event.getSource()).thenReturn(SoundPacketEvent.SOURCE_GROUP);
            voice.onSound(event); verify(event).cancel();
        }
    }
    @Test void forwardedSoundUsesPacketSenderWhenApiSuppliesNoSenderConnectionAndAvoidsDuplicates() {
        var event = mock(StaticSoundPacketEvent.class, RETURNS_DEEP_STUBS);
        var receiver = connection(dead); var id = alive.getUniqueId();
        when(event.getSenderConnection()).thenReturn(null); when(event.getReceiverConnection()).thenReturn(receiver);
        when(event.getPacket().getSender()).thenReturn(id);
        when(event.getPacket().getChannelId()).thenReturn(routing.snapshot().get(id).channel());
        when(event.getSource()).thenReturn(SoundPacketEvent.SOURCE_PLUGIN);
        voice.onSound(event); verify(event, never()).cancel();
        when(event.getPacket().getChannelId()).thenReturn(UUID.randomUUID()); voice.onSound(event); verify(event).cancel();
        clearInvocations(event);
        when(event.getPacket().getChannelId()).thenReturn(routing.snapshot().get(id).channel());
        when(first.isSpectator(dead.getUniqueId())).thenReturn(false); routing.refresh();
        voice.onSound(event); verify(event).cancel();
    }
    @Test void minigameDeathsUseSpectatorVoiceAndOnlyHearTheirOwnEvent() {
        var modes = mock(me.advait.contender.minigame.MinigameManager.class);
        when(server.plugin.getMinigameManager()).thenReturn(modes);
        when(duels.getDuel(any(UUID.class))).thenReturn(null);
        UUID game = UUID.randomUUID();
        when(modes.activityId(alive.getUniqueId())).thenReturn(game);
        when(modes.activityId(dead.getUniqueId())).thenReturn(game);
        when(modes.activityId(other.getUniqueId())).thenReturn(UUID.randomUUID());
        when(modes.isSpectator(dead.getUniqueId())).thenReturn(true);
        routing.refresh(); var states = routing.snapshot();
        assertTrue(states.get(dead.getUniqueId()).spectator());
        assertTrue(VoiceRouting.hearsSpectatorChannel(states.get(alive.getUniqueId()), states.get(dead.getUniqueId())));
        assertFalse(VoiceRouting.hearsSpectatorChannel(states.get(other.getUniqueId()), states.get(dead.getUniqueId())));
        assertFalse(VoiceRouting.hearsSpectatorChannel(states.get(dead.getUniqueId()), states.get(alive.getUniqueId())));
    }
    @Test void legacyBlanketMuteDefaultsMigrateOnceAndLaterManualSettingsArePreserved() throws Exception {
        var saved = new YamlConfiguration(); saved.set("mute-spectator-voice-chat", true); saved.set("deafen-spectator-voice-chat", true);
        saved.save(folder.resolve("chat_settings.yml").toFile());
        var migrated = new ChatSettings(server.plugin);
        assertFalse(migrated.isMuteSpectatorVoiceChat()); assertFalse(migrated.isDeafenSpectatorVoiceChat());
        migrated.setMuteSpectatorVoiceChat(true); migrated.setDeafenSpectatorVoiceChat(true); migrated.save();
        var reloaded = new ChatSettings(server.plugin);
        assertTrue(reloaded.isMuteSpectatorVoiceChat()); assertTrue(reloaded.isDeafenSpectatorVoiceChat());
    }
    @Test void diagnosticsReadCurrentClientConnectionAndGroupWithoutChangingEither() {
        var event = mock(MicrophonePacketEvent.class, RETURNS_DEEP_STUBS);
        UUID id = alive.getUniqueId();
        var sender = connection(alive);
        when(event.getSenderConnection()).thenReturn(sender);
        var builder = mock(StaticSoundPacket.Builder.class, RETURNS_SELF);
        when(event.getPacket().staticSoundPacketBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(StaticSoundPacket.class));
        when(event.getVoicechat().getConnectionOf(id)).thenReturn(sender);
        when(sender.isInstalled()).thenReturn(true);
        when(sender.isConnected()).thenReturn(true);
        when(sender.getGroup()).thenReturn(null);

        voice.onMicrophone(event);
        assertTrue(routing.diagnostics().describe(id).contains("Voice connection: Connected; voice enabled; no group"));
        when(sender.isDisabled()).thenReturn(true);
        assertTrue(routing.diagnostics().describe(id).contains("Voice connection: Connected; voice disabled on client; no group"));
        when(sender.isDisabled()).thenReturn(false);
        var group = mock(de.maxhenkel.voicechat.api.Group.class);
        when(group.getName()).thenReturn("Friends"); when(sender.getGroup()).thenReturn(group);
        assertTrue(routing.diagnostics().describe(id).contains("Voice connection: Connected; voice enabled; group: Friends"));
        when(sender.isConnected()).thenReturn(false);
        assertTrue(routing.diagnostics().describe(id).contains("Voice connection: Disconnected"));
        when(event.getVoicechat().getConnectionOf(id)).thenReturn(null);
        assertTrue(routing.diagnostics().describe(id).contains("Voice connection: No voice connection"));
        verify(sender, never()).setConnected(anyBoolean());
        verify(sender, never()).setDisabled(anyBoolean());
        verify(sender, never()).setGroup(any());
    }
}
