package me.advait.contender.tournament;

import me.advait.contender.arena.ArenaManager;
import me.advait.contender.duel.*;
import me.advait.contender.kit.*;
import me.advait.contender.map.*;
import me.advait.contender.tab.TabManager;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.vote.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TournamentRecoveryTest {
    @TempDir Path folder;

    @Test void unexpectedCancellationPausesInsteadOfReplayingTheSamePairing() {
        try (var f = new Fixture()) {
            f.manager.resume();
            var match = f.tournament.matches().getFirst();
            assertEquals(TournamentMatch.Status.PLAYING, match.status());
            clearInvocations(f.tab);

            f.complete(cancelled());

            assertFalse(f.tournament.isRunning());
            assertEquals(TournamentMatch.Status.WAITING, match.status());
            assertNull(match.result(), "An interrupted match must not award points");
            assertTrue(f.manager.playing().isEmpty());
            assertEquals("Match 1 stopped unexpectedly. Check the server log before resuming.", f.manager.waitingReason());
            verify(f.tab).refresh();
            f.tick.run();
            assertEquals(1, f.started.size(), "A ready arena must not immediately restart the failed match");

            f.manager.resume();
            assertTrue(f.tournament.isRunning());
            assertEquals(2, f.started.size());
            assertFalse(f.manager.waitingReason().contains("unexpectedly"));
        }
    }

    @Test void synchronousCancellationDuringStartDoesNotLoopOrKeepAStaleDuel() {
        try (var f = new Fixture()) {
            f.onStart = completion -> {
                assertEquals(1, f.started.size(), "The scheduler must stop during this same tick");
                f.freeArenas.incrementAndGet();
                completion.accept(cancelled());
            };

            f.manager.resume();

            assertFalse(f.tournament.isRunning());
            assertEquals(1, f.started.size());
            assertTrue(f.manager.playing().isEmpty(), "Do not register a duel whose callback already finished");
            assertEquals(TournamentMatch.Status.WAITING, f.tournament.matches().getFirst().status());
        }
    }

    @ParameterizedTest
    @EnumSource(value = DuelResult.Reason.class, names = {"FINISHED", "FORFEIT"})
    void scoredResultsStillAdvanceTheBracket(DuelResult.Reason reason) {
        try (var f = new Fixture()) {
            f.manager.resume();
            f.complete(new DuelResult(reason, 2, 0, 1));
            assertTrue(f.tournament.isRunning());
            assertEquals(TournamentMatch.Status.FINISHED, f.tournament.matches().getFirst().status());
            assertEquals(1, f.tournament.matches().stream().filter(m -> m.status() == TournamentMatch.Status.FINISHED).count());

            f.tick.run();

            assertEquals(2, f.started.size());
            assertFalse(f.manager.waitingReason().contains("unexpectedly"));
        }
    }

    @Test void deliberatePairingCancellationKeepsThePauseWithoutReportingAFailure() {
        try (var f = new Fixture()) {
            f.manager.resume();
            assertTrue(f.manager.cancelDuel(f.started.getFirst()));
            f.complete(cancelled());

            assertFalse(f.tournament.isRunning());
            assertEquals("", f.manager.waitingReason());
            f.tick.run();
            assertEquals(1, f.started.size());
            verify(f.logger, never()).warning(anyString());
        }
    }

    @Test void wholeCancellationAndManualPauseClearAnOldFailureReason() {
        try (var f = new Fixture()) {
            f.manager.resume();
            f.complete(cancelled());
            assertFalse(f.manager.waitingReason().isBlank());
            f.manager.pause();
            assertEquals("", f.manager.waitingReason());

            f.manager.resume();
            f.complete(cancelled());
            assertFalse(f.manager.waitingReason().isBlank());
            f.manager.cancel();
            assertTrue(f.tournament.isCancelled());
            assertEquals("", f.manager.waitingReason());
        }
    }

    @Test void voteStillPreventsNewMatchesAfterAScoredResult() {
        try (var f = new Fixture()) {
            f.manager.resume();
            when(f.votes.isVoteActive()).thenReturn(true);
            f.complete(new DuelResult(DuelResult.Reason.FINISHED, 2, 0, 1));

            f.tick.run();

            assertTrue(f.tournament.isRunning());
            assertEquals(1, f.started.size());
            when(f.votes.isVoteActive()).thenReturn(false);
            f.tick.run();
            assertEquals(2, f.started.size());
        }
    }

    @Test void voteCleanupCancellationKeepsTheDeliberatePause() {
        try (var f = new Fixture()) {
            f.manager.resume();
            // VoteManager pauses the bracket before cancelling its active duels.
            f.manager.pause();
            when(f.votes.isVoteActive()).thenReturn(true);
            f.complete(cancelled());

            assertEquals("", f.manager.waitingReason());
            assertFalse(f.tournament.isRunning());
            f.tick.run();
            when(f.votes.isVoteActive()).thenReturn(false);
            f.tick.run();
            assertEquals(1, f.started.size());
            verify(f.logger, never()).warning(anyString());
        }
    }

    private static DuelResult cancelled() { return new DuelResult(DuelResult.Reason.CANCELLED, 1, 0, null); }

    private final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final org.mockito.MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final DuelManager duels = mock(DuelManager.class);
        final TabManager tab = mock(TabManager.class);
        final VoteManager votes = mock(VoteManager.class);
        final Logger logger = mock(Logger.class);
        final AtomicInteger freeArenas = new AtomicInteger(1);
        final List<Duel> started = new ArrayList<>();
        final List<Consumer<DuelResult>> completions = new ArrayList<>();
        final Tournament tournament;
        final TournamentManager manager;
        final StateTestServer.Scheduled tick;
        Consumer<Consumer<DuelResult>> onStart;

        Fixture() {
            when(server.plugin.getDataFolder()).thenReturn(folder.toFile());
            when(server.plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(server.plugin.getLogger()).thenReturn(logger);
            var maps = mock(MapManager.class);
            when(server.plugin.getMapManager()).thenReturn(maps);
            when(maps.getMap("map")).thenReturn(mock(ArenaMap.class));
            var kits = mock(KitManager.class);
            when(server.plugin.getKitManager()).thenReturn(kits);
            when(kits.getKit("kit")).thenReturn(mock(Kit.class));
            var arenas = mock(ArenaManager.class);
            when(server.plugin.getArenaManager()).thenReturn(arenas);
            when(arenas.available("map")).thenAnswer(call -> freeArenas.get());
            when(server.plugin.getDuelManager()).thenReturn(duels);
            when(duels.isEligible(any())).thenReturn(true);
            when(server.plugin.getTabManager()).thenReturn(tab);
            when(server.plugin.getVoteManager()).thenReturn(votes);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(mock(Player.class));
            when(duels.startDuel(any(DuelSetup.class), any())).thenAnswer(call -> {
                freeArenas.decrementAndGet();
                Duel duel = mock(Duel.class);
                Consumer<DuelResult> completion = call.getArgument(1);
                started.add(duel);
                completions.add(completion);
                if (onStart != null) onStart.accept(completion);
                return duel;
            });
            tournament = new Tournament(UUID.randomUUID(), "Sword", "map", "kit", List.of(
                    entry("Alice"), entry("Bob"), entry("Carol"), entry("Dan")), false, false, 3, 10, 2);
            manager = new TournamentManager(server.plugin);
            manager.enable();
            manager.create(tournament);
            tick = server.scheduled.getLast();
        }

        void complete(DuelResult result) {
            freeArenas.incrementAndGet();
            completions.getLast().accept(result);
        }

        private TournamentEntry entry(String name) { return new TournamentEntry(name, List.of(UUID.randomUUID())); }

        @Override public void close() { manager.disable(); bukkit.close(); server.close(); }
    }
}
