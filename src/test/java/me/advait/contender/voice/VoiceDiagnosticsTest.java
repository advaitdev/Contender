package me.advait.contender.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VoiceDiagnosticsTest {
    @Test void normalVoiceModeDoesNotReportInactiveMutesOrQueryTheVoiceApi() {
        var diagnostics = new VoiceDiagnostics();
        UUID player = UUID.randomUUID();
        diagnostics.update(Map.of(player, new VoiceRouting.Member(null, true, true, true, false, UUID.randomUUID())));
        diagnostics.connection(id -> { throw new AssertionError("Normal voice mode must not query the voice API"); });
        diagnostics.useNormalVoiceChat();

        assertEquals(List.of("Simple Voice Chat handles audio normally.",
                "Contender's voice filtering and private spectator channel are disabled.",
                "Saved Contender mute settings do not apply."), diagnostics.describe(player));
    }

    @Test void reportsMicrophoneDeliveryAndUpstreamBlocksSeparatelyForEachPlayer() {
        var diagnostics = new VoiceDiagnostics();
        UUID speaker = UUID.randomUUID(), receiver = UUID.randomUUID();
        diagnostics.update(Map.of(speaker, lobby(), receiver, lobby()));

        diagnostics.microphone(speaker, false);
        diagnostics.microphone(speaker, true);
        diagnostics.blockedMicrophone(speaker, "Speaking is muted");
        diagnostics.outgoing(receiver, null, false);
        diagnostics.outgoing(receiver, "Hearing is deafened", false);
        diagnostics.outgoing(receiver, "A different plugin blocked this", true);

        var speakerStatus = diagnostics.describe(speaker);
        assertCounts(speakerStatus, 2, 1, 0, 0, 1, 0);
        assertTrue(speakerStatus.contains("Last Contender block: Speaking is muted"));
        assertFalse(speakerStatus.contains("Last microphone packet: None"));
        var receiverStatus = diagnostics.describe(receiver);
        assertCounts(receiverStatus, 0, 0, 1, 1, 0, 1);
        assertTrue(receiverStatus.contains("Last microphone packet: None"));
        assertTrue(receiverStatus.contains("Last Contender block: Hearing is deafened"));
        assertFalse(receiverStatus.stream().anyMatch(line -> line.contains("A different plugin blocked this")),
                "An upstream cancellation must not be recorded as a Contender block");
    }

    @Test void refreshChangesPolicyWithoutResettingConnectionCounters() {
        var diagnostics = new VoiceDiagnostics();
        UUID player = UUID.randomUUID();
        diagnostics.update(Map.of(player, lobby()));
        diagnostics.microphone(player, false);
        diagnostics.outgoing(player, null, false);

        diagnostics.update(Map.of(player, new VoiceRouting.Member(null, true, true, true,
                false, UUID.randomUUID(), UUID.randomUUID())));

        var status = diagnostics.describe(player);
        assertCounts(status, 1, 0, 1, 0, 0, 0);
        assertTrue(status.contains("Voice channel: Spectators"));
        assertTrue(status.contains("Contender speaking: Muted"));
        assertTrue(status.contains("Contender hearing: Deafened"));
        assertTrue(status.contains("Hear the match: Blocked"));
    }

    @Test void quittingClearsCountersAndLatePacketsCannotCarryThemIntoTheNextConnection() {
        var diagnostics = new VoiceDiagnostics();
        UUID player = UUID.randomUUID(), remaining = UUID.randomUUID();
        diagnostics.update(Map.of(player, lobby(), remaining, lobby()));
        diagnostics.microphone(player, false);
        diagnostics.blockedMicrophone(player, "Old connection");
        diagnostics.microphone(remaining, false);

        diagnostics.update(Map.of(remaining, lobby()));
        diagnostics.microphone(player, true);
        diagnostics.blockedMicrophone(player, "Late microphone");
        diagnostics.outgoing(player, "Late audio", false);
        var absent = diagnostics.describe(player);
        assertTrue(absent.contains("Player is missing from Contender's voice routing."));
        assertFalse(absent.stream().anyMatch(line -> line.startsWith("Microphone packets")));
        assertFalse(absent.stream().anyMatch(line -> line.contains("Old connection") || line.contains("Late")));

        diagnostics.update(Map.of(player, lobby(), remaining, lobby()));

        assertCounts(diagnostics.describe(player), 0, 0, 0, 0, 0, 0);
        assertTrue(diagnostics.describe(player).contains("Last microphone packet: None"));
        assertFalse(diagnostics.describe(player).stream().anyMatch(line -> line.startsWith("Last Contender block:")));
        assertCounts(diagnostics.describe(remaining), 1, 0, 0, 0, 0, 0);
    }

    @Test void unknownPlayersDoNotAccumulateTrafficBeforeTheyJoin() {
        var diagnostics = new VoiceDiagnostics();
        UUID unknown = UUID.randomUUID();
        for (int packet = 0; packet < 100; packet++) {
            diagnostics.microphone(unknown, true);
            diagnostics.blockedMicrophone(unknown, "Unknown sender");
            diagnostics.outgoing(unknown, null, false);
            diagnostics.outgoing(unknown, "Unknown receiver", false);
            diagnostics.outgoing(unknown, null, true);
        }
        assertEquals(List.of("Contender voice hook: Not registered", "Voice connection: Not available",
                "Player is missing from Contender's voice routing."), diagnostics.describe(unknown));

        diagnostics.update(Map.of(unknown, lobby()));

        assertCounts(diagnostics.describe(unknown), 0, 0, 0, 0, 0, 0);
    }

    @Test void reportsCurrentIntegrationAndConnectionStatusWithoutClaimingAudioWasHeard() {
        var diagnostics = new VoiceDiagnostics();
        UUID player = UUID.randomUUID();
        diagnostics.update(Map.of(player, lobby()));
        diagnostics.integration("Registered");
        diagnostics.connection(id -> id.equals(player) ? "Connected" : "Disconnected");
        diagnostics.outgoing(player, null, false);

        var status = diagnostics.describe(player);

        assertTrue(status.contains("Contender voice hook: Registered"));
        assertTrue(status.contains("Voice connection: Connected"));
        assertTrue(status.contains("Voice channel: Lobby"));
        assertTrue(status.contains("Contender speaking: Allowed"));
        assertTrue(status.contains("Contender hearing: Allowed"));
        assertTrue(status.contains("Audio packets allowed by Contender: 1"));
        assertFalse(status.stream().anyMatch(line -> line.toLowerCase().contains("delivered")
                || line.toLowerCase().contains("heard successfully")));
        diagnostics.connection(id -> "Disabled in the voice client");
        assertTrue(diagnostics.describe(player).contains("Voice connection: Disabled in the voice client"));
        assertThrows(UnsupportedOperationException.class, () -> status.add("extra"));
    }

    @Test void connectionProbeFailureStillReportsRoutingAndPacketCounts() {
        var diagnostics = new VoiceDiagnostics();
        UUID player = UUID.randomUUID();
        diagnostics.update(Map.of(player, new VoiceRouting.Member(null, false, false, false,
                true, UUID.randomUUID(), UUID.randomUUID())));
        diagnostics.microphone(player, false);
        diagnostics.connection(id -> { throw new IllegalStateException("Connection is shutting down"); });

        var status = assertDoesNotThrow(() -> diagnostics.describe(player));

        assertTrue(status.contains("Voice connection: Could not read status"));
        assertTrue(status.contains("Voice channel: Contestants"));
        assertCounts(status, 1, 0, 0, 0, 0, 0);
        assertFalse(status.stream().anyMatch(line -> line.contains("Connection is shutting down")));
    }

    @Test void concurrentPacketsKeepExactIndependentTotalsDuringRoutingRefresh() throws Exception {
        var diagnostics = new VoiceDiagnostics();
        UUID player = UUID.randomUUID(), quiet = UUID.randomUUID();
        var members = Map.of(player, lobby(), quiet, lobby());
        diagnostics.update(members);
        int workers = 8, packetsPerWorker = 1000;
        var start = new CountDownLatch(1);
        List<Future<?>> finished = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(workers + 1)) {
            for (int worker = 0; worker < workers; worker++) {
                finished.add(pool.submit(() -> {
                    start.await();
                    for (int packet = 0; packet < packetsPerWorker; packet++) {
                        diagnostics.microphone(player, false);
                        diagnostics.microphone(player, true);
                        diagnostics.blockedMicrophone(player, "Speaking is muted");
                        diagnostics.outgoing(player, null, false);
                        diagnostics.outgoing(player, null, false);
                        diagnostics.outgoing(player, "Hearing is deafened", false);
                        diagnostics.outgoing(player, null, true);
                        diagnostics.outgoing(player, null, true);
                        diagnostics.outgoing(player, null, true);
                    }
                    return null;
                }));
            }
            finished.add(pool.submit(() -> {
                start.await();
                for (int refresh = 0; refresh < packetsPerWorker; refresh++) diagnostics.update(members);
                return null;
            }));
            start.countDown();
            for (Future<?> worker : finished) worker.get(15, TimeUnit.SECONDS);
        }

        long packets = (long) workers * packetsPerWorker;
        assertCounts(diagnostics.describe(player), packets * 2, packets, packets * 2, packets, packets, packets * 3);
        assertCounts(diagnostics.describe(quiet), 0, 0, 0, 0, 0, 0);
    }

    private static VoiceRouting.Member lobby() {
        return new VoiceRouting.Member(null, false, false, false, false, UUID.randomUUID());
    }

    private static void assertCounts(List<String> status, long microphones, long microphoneBlocks,
                                     long allowed, long outgoingBlocks, long upstreamMicrophones, long upstreamAudio) {
        assertTrue(status.contains("Microphone packets received: " + microphones), status.toString());
        assertTrue(status.contains("Microphone packets blocked by Contender: " + microphoneBlocks), status.toString());
        assertTrue(status.contains("Audio packets allowed by Contender: " + allowed), status.toString());
        assertTrue(status.contains("Audio packets blocked by Contender: " + outgoingBlocks), status.toString());
        if (upstreamMicrophones != 0 || upstreamAudio != 0) {
            assertTrue(status.contains("Already blocked before Contender: " + upstreamMicrophones + " microphone, " + upstreamAudio + " audio"), status.toString());
        }
    }
}
