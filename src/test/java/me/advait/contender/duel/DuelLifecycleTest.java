package me.advait.contender.duel;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DuelLifecycleTest {
    @Test void cleanupFailureKeepsAnAlreadyCompletedMatchResult() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            when(server.plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
            DuelManager manager = mock(DuelManager.class);
            Duel duel = duel(server, manager);
            duel.getTeam1().incrementScore();
            duel.endDuel();
            var delayedCleanup = server.scheduled.getFirst();
            duel.resetFailed(new IllegalStateException("Another player entered the copy."));
            assertTrue(duel.isFinished());
            assertEquals(DuelResult.Reason.FINISHED, duel.getResult().reason());
            assertEquals(1, duel.getResult().winner());
            assertEquals(1, duel.getResult().team1Score());
            delayedCleanup.run();
            verify(manager, times(1)).endDuel(duel);
        }
    }

    @Test void cleanupFailureKeepsAForfeitAndMidMatchFailuresStayCancelled() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            when(server.plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
            var setup = new DuelSetup(UUID.randomUUID());
            setup.setSelectedMap(new ArenaMap("test"));
            UUID leaving = UUID.randomUUID();
            setup.getTeam1().addPlayer(leaving); setup.getTeam2().addPlayer(UUID.randomUUID());
            setup.getTeam1().setName("First"); setup.getTeam2().setName("Second");
            Duel duel = spy(new Duel(server.plugin, mock(DuelManager.class), setup, null, true));
            doNothing().when(duel).rollbackArena(any());
            duel.forfeit(leaving);
            duel.resetFailed(new IllegalStateException("Reset failed."));
            assertTrue(duel.isFinished());
            assertEquals(DuelResult.Reason.FORFEIT, duel.getResult().reason());
            assertEquals(2, duel.getResult().winner());

            Duel interrupted = duel(server, mock(DuelManager.class));
            interrupted.getTeam1().incrementScore();
            interrupted.resetFailed(new IllegalStateException("Reset failed between rounds."));
            assertTrue(interrupted.isFinished());
            assertEquals(DuelResult.Reason.CANCELLED, interrupted.getResult().reason());
        }
    }

    private Duel duel(StateTestServer server, DuelManager manager) {
        var setup = new DuelSetup(UUID.randomUUID());
        setup.setSelectedMap(new ArenaMap("test"));
        return spy(new Duel(server.plugin, manager, setup));
    }

    @Test
    void sortingTransitionsOnceAndUnregistersBeforeCombat() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            Duel duel = duel(server, mock(DuelManager.class));
            doNothing().when(duel).prepareRound();
            var sorting = new SortingState(duel);
            duel.setState(sorting);
            var timer = server.scheduled.getFirst();
            for (int second = 0; second < duel.getPreRoundDelay(); second++) timer.run();
            assertInstanceOf(ActiveState.class, duel.getState());
            assertTrue(duel.isCombatActive());
            assertFalse(sorting.isEnabled());
            assertEquals(1, server.handlers.getRegisteredListeners().length);
            assertSame(duel.getState(), server.handlers.getRegisteredListeners()[0].getListener());
            timer.run();
            verify(duel, times(1)).captureInventories();
            verify(duel, times(1)).prepareRound();
            for (int second = duel.getPreRoundDelay(); second >= 1; second--) verify(duel).broadcastCountdown(second);
            verify(duel).clearCountdown();
            verify(timer.task()).cancel();
            duel.shutdown();
        }
    }

    @Test
    void forcedEndIgnoresOldRollbackAndWaitsForItsOwnResetBeforeRelease() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            DuelManager manager = mock(DuelManager.class);
            Duel duel = duel(server, manager);
            List<Runnable> resets = new ArrayList<>();
            doAnswer(call -> { resets.add(call.getArgument(0)); return null; }).when(duel).rollbackArena(any());
            duel.setState(new RoundEndState(duel, null));
            assertEquals(1, resets.size());
            duel.forceEnd();
            assertTrue(duel.getState().isEnding());
            assertEquals(2, resets.size());
            assertFalse(duel.isFinished());
            verify(manager, never()).endDuel(duel);
            resets.get(0).run();
            assertTrue(server.scheduled.isEmpty(), "Old rollback must not schedule another round");
            resets.get(1).run();
            assertTrue(duel.isFinished());
            assertFalse(duel.getState().isEnabled());
            assertEquals(0, server.handlers.getRegisteredListeners().length);
            verify(manager).endDuel(duel);
            resets.get(1).run();
            verify(manager, times(1)).endDuel(duel);
        }
    }

    @Test void forceCancellationFinishesEvenWhenRollbackNeverCompletes() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            DuelManager manager = mock(DuelManager.class);
            Duel duel = duel(server, manager);
            List<Runnable> resets = new ArrayList<>();
            doAnswer(call -> { resets.add(call.getArgument(0)); return null; }).when(duel).rollbackArena(any());
            duel.forceEnd();
            assertFalse(duel.isFinished());
            duel.forceCancel();
            assertTrue(duel.isFinished());
            assertEquals(DuelResult.Reason.CANCELLED, duel.getResult().reason());
            assertEquals(0, server.handlers.getRegisteredListeners().length);
            resets.getFirst().run();
            duel.forceCancel();
            verify(manager, times(1)).endDuel(duel);
            assertTrue(server.scheduled.isEmpty());
        }
    }

    @Test void betweenRoundCountdownEndsBeforeCombatAndCancelledCallbacksCannotReshowIt() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            Duel duel = duel(server, mock(DuelManager.class));
            doNothing().when(duel).prepareRound();
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(duel).rollbackArena(any());
            duel.setState(new RoundEndState(duel, null));
            var callbacks = List.copyOf(server.scheduled);
            for (int index = 0; index < 3; index++) {
                callbacks.get(index).run(); verify(duel).broadcastCountdown(3 - index);
            }
            callbacks.get(3).run(); assertInstanceOf(ActiveState.class, duel.getState()); verify(duel).clearCountdown();
            clearInvocations(duel); callbacks.forEach(StateTestServer.Scheduled::run);
            verify(duel, never()).broadcastCountdown(anyInt()); duel.shutdown();
        }
    }

    @Test
    void stoppingDuringResultDelayCancelsTheDelayedCleanup() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            DuelManager manager = mock(DuelManager.class);
            Duel duel = duel(server, manager);
            doNothing().when(duel).rollbackArena(any());
            duel.endDuel();
            var cleanup = server.scheduled.getFirst();
            assertEquals(60L, cleanup.delay());
            duel.shutdown();
            cleanup.run();
            verify(cleanup.task()).cancel();
            verify(duel, never()).rollbackArena(any());
            verify(manager).endDuel(duel);
            assertTrue(duel.isFinished());
            assertEquals(0, server.handlers.getRegisteredListeners().length);
        }
    }

    @Test
    void endingKeepsOwnershipUntilDelayedCleanupAndRollbackHaveBothFinished() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            DuelManager manager = mock(DuelManager.class);
            Duel duel = duel(server, manager);
            List<Runnable> resets = new ArrayList<>();
            doAnswer(call -> { resets.add(call.getArgument(0)); return null; }).when(duel).rollbackArena(any());
            duel.endDuel();
            duel.endDuel();
            assertEquals(1, server.scheduled.size());
            verify(manager, never()).endDuel(duel);
            server.scheduled.getFirst().run();
            verify(manager, never()).endDuel(duel);
            assertEquals(1, resets.size());
            resets.getFirst().run();
            verify(manager).endDuel(duel);
            assertTrue(duel.isFinished());
        }
    }

    @Test
    void rejectsStatesFromAnotherDuelAndCannotRestartAFinishedDuel() {
        try (var server = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            Duel first = duel(server, mock(DuelManager.class));
            Duel second = duel(server, mock(DuelManager.class));
            assertThrows(IllegalArgumentException.class, () -> first.setState(new SortingState(second)));
            first.shutdown();
            first.setState(new ActiveState(first));
            first.start();
            assertTrue(first.isFinished());
            assertTrue(server.scheduled.isEmpty());
        }
    }
}
