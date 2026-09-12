package me.advait.contender.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import me.advait.contender.chat.ChatSettings;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceChatLifecycleTest {
    @TempDir Path folder;
    private final StateTestServer server = new StateTestServer();
    private final DuelManager duels = mock(DuelManager.class);
    private final RoleManager roles = mock(RoleManager.class);
    private final VoicechatServerApi api = mock(VoicechatServerApi.class);
    private final Player alice = player(), bob = player();
    private final RetainedHandlers handlers = new RetainedHandlers();
    private VoiceRouting routing;

    @BeforeEach void setup() {
        when(server.plugin.getDataFolder()).thenReturn(folder.toFile());
        var settings = new ChatSettings(server.plugin);
        when(server.plugin.getChatSettings()).thenReturn(settings);
        when(server.plugin.getDuelManager()).thenReturn(duels);
        when(server.plugin.getRoleManager()).thenReturn(roles);
        when(roles.isContestant(any())).thenReturn(true);
        doReturn(List.of(alice, bob)).when(server.server).getOnlinePlayers();
        routing = new VoiceRouting(server.plugin);
        routing.enable();
        new VoiceChatManager(routing).registerEvents(handlers);
    }

    @AfterEach void close() {
        if (routing != null) routing.disable();
        server.close();
    }

    @Test void disabledRoutingLeavesRetainedMicrophoneHandlerInert() throws Exception {
        when(roles.isContestant(any())).thenReturn(false);
        routing.refresh();
        var microphone = microphone(alice);
        var receiver = connection(bob);
        when(api.getConnectionOf(bob.getUniqueId())).thenReturn(receiver);
        assertTrue(routing.snapshot().get(alice.getUniqueId()).spectator());

        routing.disable();
        assertFalse(routing.isActive());
        assertTrue(routing.snapshot().isEmpty());
        clearBukkitInvocations();
        clearInvocations(api);

        onVoiceThread(() -> handlers.fire(MicrophonePacketEvent.class, microphone));

        verify(microphone, never()).cancel();
        verify(microphone, never()).getPacket();
        verifyNoInteractions(api);
        verifyNoBukkitInteractions();
    }

    @ParameterizedTest
    @MethodSource("outgoingTypes")
    void disabledRoutingLeavesEveryRetainedOutgoingHandlerInert(
            Class<? extends SoundPacketEvent<?>> type) throws Exception {
        var event = outgoing(type, alice, bob);
        routing.disable();
        clearBukkitInvocations();
        clearInvocations(api);

        onVoiceThread(() -> handlers.fireSound(type, event));

        verify(event, never()).cancel();
        verifyNoInteractions(api);
        verifyNoBukkitInteractions();
    }

    @Test void cancelledRefreshCannotReactivateOldVoiceRouting() {
        routing.disable();
        clearBukkitInvocations();

        for (var task : List.copyOf(server.scheduled)) task.run();

        assertFalse(routing.isActive());
        assertTrue(routing.snapshot().isEmpty());
        verifyNoBukkitInteractions();
    }

    @Test void lobbyContestantMicrophonePassesWithDefaultSettings() throws Exception {
        var microphone = microphone(alice);
        assertTrue(routing.isActive());
        assertFalse(routing.snapshot().get(alice.getUniqueId()).muted());
        assertFalse(routing.snapshot().get(alice.getUniqueId()).spectator());
        clearBukkitInvocations();

        onVoiceThread(() -> handlers.fire(MicrophonePacketEvent.class, microphone));

        verify(microphone, never()).cancel();
        verify(api, never()).sendStaticSoundPacketTo(any(), any());
        verifyNoBukkitInteractions();
    }

    @ParameterizedTest
    @MethodSource("outgoingTypes")
    void lobbyContestantsHearEveryOutgoingPacketTypeWithDefaultSettings(
            Class<? extends SoundPacketEvent<?>> type) throws Exception {
        var event = outgoing(type, alice, bob);
        clearBukkitInvocations();

        onVoiceThread(() -> handlers.fireSound(type, event));

        verify(event, never()).cancel();
        verifyNoInteractions(api);
        verifyNoBukkitInteractions();
    }

    @Test void joinPublishesTheNewContestantBeforeTheNextScheduledRefresh() throws Exception {
        Player newcomer = player();
        UUID newcomerId = newcomer.getUniqueId();
        assertFalse(routing.snapshot().containsKey(newcomerId));
        doReturn(List.of(alice, bob, newcomer)).when(server.server).getOnlinePlayers();

        routing.onJoin(new PlayerJoinEvent(newcomer, Component.empty()));

        assertNotNull(routing.snapshot().get(newcomerId));
        var microphone = microphone(newcomer);
        var outgoing = outgoing(EntitySoundPacketEvent.class, newcomer, bob);
        clearBukkitInvocations();
        clearInvocations(newcomer);

        onVoiceThread(() -> {
            handlers.fire(MicrophonePacketEvent.class, microphone);
            handlers.fireSound(EntitySoundPacketEvent.class, outgoing);
        });

        verify(microphone, never()).cancel();
        verify(outgoing, never()).cancel();
        verifyNoBukkitInteractions();
        verifyNoInteractions(newcomer);
    }

    private static Stream<Class<? extends SoundPacketEvent<?>>> outgoingTypes() {
        return Stream.of(EntitySoundPacketEvent.class, LocationalSoundPacketEvent.class, StaticSoundPacketEvent.class);
    }

    private static Player player() {
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static VoicechatConnection connection(Player player) {
        UUID id = player.getUniqueId();
        var connection = mock(VoicechatConnection.class, RETURNS_DEEP_STUBS);
        when(connection.getPlayer().getUuid()).thenReturn(id);
        return connection;
    }

    private MicrophonePacketEvent microphone(Player speaker) {
        var event = mock(MicrophonePacketEvent.class, RETURNS_DEEP_STUBS);
        var sender = connection(speaker);
        when(event.getSenderConnection()).thenReturn(sender);
        when(event.getVoicechat()).thenReturn(api);
        var builder = mock(StaticSoundPacket.Builder.class, RETURNS_SELF);
        var packet = mock(StaticSoundPacket.class);
        when(event.getPacket().staticSoundPacketBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(packet);
        clearInvocations(event);
        return event;
    }

    private SoundPacketEvent<?> outgoing(Class<? extends SoundPacketEvent<?>> type, Player speaker, Player listener) {
        var event = mock(type, RETURNS_DEEP_STUBS);
        var sender = connection(speaker);
        var receiver = connection(listener);
        UUID speakerId = speaker.getUniqueId();
        when(event.getSenderConnection()).thenReturn(sender);
        when(event.getReceiverConnection()).thenReturn(receiver);
        when(event.getVoicechat()).thenReturn(api);
        when(event.getSource()).thenReturn(SoundPacketEvent.SOURCE_PROXIMITY);
        SoundPacket packet = (SoundPacket) event.getPacket();
        when(packet.getSender()).thenReturn(speakerId);
        when(packet.getChannelId()).thenReturn(speakerId);
        return event;
    }

    private void clearBukkitInvocations() {
        clearInvocations(server.plugin, server.server, server.scheduler, server.plugins, duels, roles, alice, bob);
    }

    private void verifyNoBukkitInteractions() {
        verifyNoInteractions(server.plugin, server.server, server.scheduler, server.plugins, duels, roles, alice, bob);
    }

    private static void onVoiceThread(Runnable callback) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("voice-routing-test").factory())) {
            executor.submit(callback).get(5, TimeUnit.SECONDS);
        }
    }

    /** Simple Voice Chat retains these callbacks even after Bukkit unregisters the routing listener. */
    private static final class RetainedHandlers implements EventRegistration {
        private final Map<Class<?>, Consumer<?>> callbacks = new HashMap<>();

        @Override public <T extends Event> void registerEvent(Class<T> type, Consumer<T> callback, int priority) {
            callbacks.put(type, callback);
        }

        @SuppressWarnings("unchecked")
        <T extends Event> void fire(Class<T> type, T event) {
            var callback = (Consumer<T>) callbacks.get(type);
            assertNotNull(callback, "Missing voice callback for " + type.getSimpleName());
            callback.accept(event);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void fireSound(Class<? extends SoundPacketEvent<?>> type, SoundPacketEvent<?> event) {
            fire((Class) type, event);
        }
    }
}
