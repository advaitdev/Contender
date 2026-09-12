package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.race.RaceManager;
import me.advait.contender.tournament.TournamentManager;
import me.advait.contender.vote.VoteManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinigameManagerTest {
    @TempDir Path folder;
    private Contender plugin() {
        var plugin = mock(Contender.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getTournamentManager()).thenReturn(mock(TournamentManager.class, RETURNS_DEEP_STUBS));
        when(plugin.getVoteManager()).thenReturn(mock(VoteManager.class));
        when(plugin.getDuelManager()).thenReturn(mock(me.advait.contender.duel.DuelManager.class));
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        when(plugin.getTournamentManager().current()).thenReturn(null);
        when(plugin.getTournamentManager().playing()).thenReturn(java.util.Map.of());
        return plugin;
    }
    private MinigameMode mode(String id) {
        var mode = mock(MinigameMode.class);
        when(mode.id()).thenReturn(id); when(mode.displayName()).thenReturn(id);
        when(mode.eventId()).thenReturn(UUID.randomUUID()); when(mode.terminal()).thenReturn(true);
        return mode;
    }
    @Test void refusesReplacingReadyEventsAndStartingDuringOtherModesCleanup() {
        var manager = new MinigameManager(plugin()); var race = mode("mace_race"); manager.register(race);
        when(race.terminal()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> manager.beforeCreate("combo"));
        when(race.terminal()).thenReturn(true); when(race.busy()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> manager.beforeStart("combo"));
        assertThrows(IllegalStateException.class, manager::selectRoundRobin);
    }
    @Test void selectionSurvivesRestartAndRoundRobinDoesNotReviveLegacyRace() {
        var plugin = plugin(); var race = mock(RaceManager.class); when(plugin.getRaceManager()).thenReturn(race);
        var manager = new MinigameManager(plugin); var combo = mode("combo"); manager.register(combo);
        manager.select("combo");
        var reload = new MinigameManager(plugin); reload.register(combo); assertSame(combo, reload.selected());
        reload.selectRoundRobin(); verify(race).selectRoundRobin();
        assertNull(new MinigameManager(plugin).selected());
    }
    @Test void ownershipAndRecoveryIncludeEveryMode() {
        var manager = new MinigameManager(plugin()); var first = mode("manhunt"); var second = mode("combo");
        manager.register(first); manager.register(second);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(first.owns(a)).thenReturn(true); when(first.owns(b)).thenReturn(true); when(second.owns(c)).thenReturn(true);
        when(first.isPlaying(a)).thenReturn(true); when(first.isSpectator(b)).thenReturn(true);
        when(second.pendingReturn(c)).thenReturn(true);
        assertTrue(manager.sameEvent(a, b)); assertFalse(manager.sameEvent(a, c));
        assertTrue(manager.isPlaying(a)); assertTrue(manager.isSpectator(b)); assertTrue(manager.pendingReturn(c));
        manager.withdraw(a); verify(first).withdraw(a); verify(second).withdraw(a);
    }
    @Test void forceCancelClearsInterruptedCleanupAndSelectionBeforeCreatingAnotherMode() {
        var plugin = plugin();
        var manager = new MinigameManager(plugin);
        var combo = mode("combo");
        var race = mode("mace_race");
        var busy = new AtomicBoolean(true);
        when(combo.busy()).thenAnswer(call -> busy.get());
        doAnswer(call -> { busy.set(false); return null; }).when(combo).forceCancel();
        manager.register(combo); manager.register(race); manager.select("combo");
        assertThrows(IllegalStateException.class, () -> manager.beforeCreate("mace_race"));

        assertTrue(manager.forceCancelAll().isEmpty());
        assertDoesNotThrow(() -> manager.beforeCreate("mace_race"));
        assertNull(manager.selected());
        assertNull(new MinigameManager(plugin).selected());
        var order = inOrder(plugin.getTournamentManager(), plugin.getDuelManager(), combo, race, plugin.getVoteManager());
        order.verify(plugin.getTournamentManager()).forceCancel();
        order.verify(plugin.getDuelManager()).forceCancelAll();
        order.verify(combo).forceCancel();
        order.verify(race).forceCancel();
        order.verify(plugin.getVoteManager()).forceCancel();
        assertTrue(manager.forceCancelAll().isEmpty());
    }
    @Test void oneCleanupFailureDoesNotSkipOtherModesVotesOrSelectionReset() {
        var plugin = plugin(); var manager = new MinigameManager(plugin);
        var combo = mode("combo"); var race = mode("mace_race");
        manager.register(combo); manager.register(race); manager.select("combo");
        doThrow(new IllegalStateException("Test cleanup failure")).when(combo).forceCancel();

        assertEquals(List.of("combo"), manager.forceCancelAll());
        verify(race).forceCancel(); verify(plugin.getVoteManager()).forceCancel();
        assertNull(manager.selected());
    }
}
