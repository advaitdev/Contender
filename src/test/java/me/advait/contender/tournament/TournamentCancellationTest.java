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
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TournamentCancellationTest {
    @TempDir Path directory;
    @Test void tournamentWaitsForStartupPreparationAndStartsOnTheNextSchedulerTick() {
        try (var env = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
            when(env.plugin.getConfig()).thenReturn(new YamlConfiguration());
            var maps = mock(MapManager.class); when(env.plugin.getMapManager()).thenReturn(maps);
            when(maps.getMap("map")).thenReturn(mock(ArenaMap.class));
            var kits = mock(KitManager.class); when(env.plugin.getKitManager()).thenReturn(kits);
            when(kits.getKit("kit")).thenReturn(mock(Kit.class));
            var arenas = mock(ArenaManager.class); when(env.plugin.getArenaManager()).thenReturn(arenas);
            when(arenas.readiness("map")).thenReturn("0 / 20 copies ready. Preparing automatically.");
            var duels = mock(DuelManager.class); when(env.plugin.getDuelManager()).thenReturn(duels);
            when(duels.isEligible(any())).thenReturn(true);
            when(env.plugin.getVoteManager()).thenReturn(mock(VoteManager.class));
            var tournament = new Tournament(UUID.randomUUID(), "Sword", "map", "kit", List.of(
                    new TournamentEntry("Alice", List.of(UUID.randomUUID())), new TournamentEntry("Bob", List.of(UUID.randomUUID()))), false, false, 3, 10, 2);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(mock(Player.class));
            Duel duel = mock(Duel.class); when(duels.startDuel(any(DuelSetup.class), any())).thenReturn(duel);
            TournamentManager manager = new TournamentManager(env.plugin);
            manager.enable(); manager.create(tournament);
            assertDoesNotThrow(manager::resume);
            assertTrue(tournament.isRunning()); assertTrue(manager.playing().isEmpty());
            assertEquals("0 / 20 copies ready. Preparing automatically.", manager.waitingReason());
            verify(duels, never()).startDuel(any(DuelSetup.class), any());

            when(arenas.available("map")).thenReturn(1, 1, 0);
            env.scheduled.getLast().run();
            assertSame(duel, manager.playing().get(1));
            verify(duels).startDuel(any(DuelSetup.class), any());
            manager.disable();
        }
    }
    @Test void cancellingAPairingPausesBeforeCleanupAndWholeCancellationRefreshesTabImmediately() {
        try (var env = new StateTestServer(); var bukkit = mockStatic(Bukkit.class)) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile()); when(env.plugin.getConfig()).thenReturn(new YamlConfiguration());
            var maps = mock(MapManager.class); when(env.plugin.getMapManager()).thenReturn(maps); when(maps.getMap("map")).thenReturn(mock(ArenaMap.class));
            var kits = mock(KitManager.class); when(env.plugin.getKitManager()).thenReturn(kits); when(kits.getKit("kit")).thenReturn(mock(Kit.class));
            var arenas = mock(ArenaManager.class); when(env.plugin.getArenaManager()).thenReturn(arenas); when(arenas.available("map")).thenReturn(1, 1, 0);
            var duels = mock(DuelManager.class); when(env.plugin.getDuelManager()).thenReturn(duels); when(duels.isEligible(any())).thenReturn(true);
            when(env.plugin.getVoteManager()).thenReturn(mock(VoteManager.class));
            var tab = mock(TabManager.class); when(env.plugin.getTabManager()).thenReturn(tab);
            var tournament = new Tournament(UUID.randomUUID(), "Sword", "map", "kit", List.of(
                    new TournamentEntry("Alice", List.of(UUID.randomUUID())), new TournamentEntry("Bob", List.of(UUID.randomUUID()))), false, false, 3, 10, 2);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(mock(Player.class));
            Duel duel = mock(Duel.class); ArgumentCaptor<Consumer<DuelResult>> completion = ArgumentCaptor.forClass(Consumer.class);
            when(duels.startDuel(any(DuelSetup.class), completion.capture())).thenReturn(duel);
            TournamentManager manager = new TournamentManager(env.plugin);
            manager.create(tournament); manager.resume(); assertTrue(tournament.isRunning());
            doAnswer(call -> { assertFalse(tournament.isRunning(), "Must pause before ending the duel"); return null; }).when(duel).forceEnd();
            assertTrue(manager.cancelDuel(duel));
            assertSame(duel, manager.playing().get(1), "Keep the arena reserved until cleanup completes");
            assertFalse(tournament.isRunning());
            clearInvocations(tab); manager.cancel();
            verify(tab, atLeastOnce()).refresh(); assertTrue(tournament.isCancelled());
            var oldCompletion = completion.getValue();
            manager.forceCancel();
            assertTrue(manager.playing().isEmpty()); assertNull(tournament.matches().getFirst().result());
            assertFalse(tournament.isRunning());

            var replacement = new Tournament(UUID.randomUUID(), "Axe", "map", "kit", tournament.entries(), false, false, 3, 10, 2);
            Duel newDuel = mock(Duel.class);
            when(duels.startDuel(any(DuelSetup.class), completion.capture())).thenReturn(newDuel);
            when(arenas.available("map")).thenReturn(1, 1, 0);
            manager.create(replacement); manager.resume();
            oldCompletion.accept(new DuelResult(DuelResult.Reason.CANCELLED, 1, 0, null));
            assertSame(newDuel, manager.playing().get(1), "A late result must not remove the replacement pairing");
            assertTrue(replacement.isRunning());
        }
    }
}
