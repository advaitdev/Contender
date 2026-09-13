package me.advait.contender.duel;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.spectator.SpectatorVisibility;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuelResetRecoveryTest {
    @Test void recoveryWhileWaitingForRespawnCannotDuplicatePlacementOrTheCountdown() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loser.dead = true;
            f.begin();
            f.latestReset().complete(null);
            StateTestServer.Scheduled poll = f.remainingOneShots().getFirst();
            assertEquals(1L, poll.delay());
            assertTrue(f.round.recoverResetFailure(new IllegalStateException("Temporary physics failure")));
            StateTestServer.Scheduled retry = f.server.scheduled.getLast();
            assertEquals(40L, retry.delay());
            int queuedBeforeRetry = f.server.scheduled.size();

            retry.run();

            assertEquals(queuedBeforeRetry, f.server.scheduled.size(), "A recovery attempt reuses the pending respawn poll");
            assertEquals(1, f.resets.size(), "Respawn waiting must never repeat the completed paste");
            f.loser.dead = false;
            poll.run();
            int queuedAfterRecovery = f.server.scheduled.size();

            poll.run();
            retry.run();

            assertEquals(queuedAfterRecovery, f.server.scheduled.size(), "Stale callbacks cannot start another countdown");
            assertEquals(List.of(20L, 40L, 60L, 80L), f.server.scheduled.subList(queuedBeforeRetry, queuedAfterRecovery)
                    .stream().map(StateTestServer.Scheduled::delay).toList());
            assertTrue(f.round.canAddSpectator());
            assertEquals(f.winnerSpawn, f.winner.location);
            assertEquals(f.loserSpawn, f.loser.location);
            verify(f.winner.player, times(1)).teleport(any(Location.class));
            verify(f.loser.player, times(1)).teleport(any(Location.class));
            assertEquals(1, f.duel.getTeam1().getScore());
            verify(f.manager, never()).endDuel(any());
        }
    }

    @Test void failedPasteRetriesTheSameRoundAndStartsOneCountdownAfterRecovery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            int roundNumber = f.duel.getCurrentRound();
            f.latestReset().completeExceptionally(new IllegalStateException("Temporary paste failure"));

            f.assertWaitingWithoutLosingTheResult(roundNumber);
            assertEquals(1, f.resets.size());
            f.runNext();
            assertEquals(2, f.resets.size());
            f.assertWaitingWithoutLosingTheResult(roundNumber);
            f.latestReset().complete(null);

            assertEquals(List.of(20L, 40L, 60L, 80L), f.remainingOneShots().stream()
                    .map(StateTestServer.Scheduled::delay).toList());
            for (int seconds = 3; seconds >= 1; seconds--) {
                f.runNext();
                verify(f.duel, times(1)).broadcastCountdown(seconds);
            }
            f.runNext();
            assertInstanceOf(ActiveState.class, f.duel.getState());
            verify(f.duel, times(1)).prepareRound();
            verify(f.arenas, times(2)).resetRound(eq(f.lease), anySet(), any());
            verify(f.arenas, never()).reset(any());
            verify(f.manager, never()).endDuel(any());
            assertEquals(1, f.duel.getTeam1().getScore());
            assertEquals(0, f.duel.getTeam2().getScore());
        }
    }

    @Test void blockedSpawnPlacementRetriesPlacementWithoutPastingAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.winner.blockTeleport = true;
            f.begin();
            int roundNumber = f.duel.getCurrentRound();
            f.latestReset().complete(null);

            f.assertWaitingWithoutLosingTheResult(roundNumber);
            f.runNext();
            f.assertWaitingWithoutLosingTheResult(roundNumber);
            assertEquals(1, f.resets.size(), "A completed paste must not repeat for a blocked teleport");
            f.winner.blockTeleport = false;
            f.runNext();

            assertEquals(f.winnerSpawn, f.winner.location);
            assertEquals(f.loserSpawn, f.loser.location);
            assertTrue(f.winner.gravity);
            assertTrue(f.loser.gravity);
            assertEquals(List.of(20L, 40L, 60L, 80L), f.remainingOneShots().stream()
                    .map(StateTestServer.Scheduled::delay).toList());
            verify(f.arenas, times(1)).resetRound(eq(f.lease), anySet(), any());
            verify(f.arenas, never()).discard(any());
            verify(f.manager, never()).endDuel(any());
            assertEquals(1, f.duel.getTeam1().getScore());
        }
    }

    @Test void forceCancellationDuringBackoffCannotRetryOrRestartTheRound() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            f.latestReset().completeExceptionally(new IllegalStateException("Temporary paste failure"));
            f.assertWaitingWithoutLosingTheResult(f.duel.getCurrentRound());
            List<StateTestServer.Scheduled> pending = List.copyOf(f.server.scheduled);

            f.duel.forceCancel();
            pending.forEach(StateTestServer.Scheduled::run);

            assertTrue(f.duel.isFinished());
            assertFalse(f.round.isEnabled());
            assertEquals(1, f.resets.size());
            assertTrue(f.winner.gravity);
            assertTrue(f.loser.gravity);
            verify(f.arenas, times(1)).resetRound(eq(f.lease), anySet(), any());
            verify(f.arenas).abandon(f.lease);
            verify(f.manager, times(1)).endDuel(f.duel);
            verify(f.duel, never()).broadcastCountdown(anyInt());
            verify(f.duel, never()).prepareRound();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<SpectatorVisibility> avatars = mockConstruction(SpectatorVisibility.class);
        final World world = mock(World.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final DuelManager manager = mock(DuelManager.class);
        final LobbyManager lobby = mock(LobbyManager.class);
        final Location winnerSpawn = new Location(world, 34, 65, 34);
        final Location loserSpawn = new Location(world, 42, 65, 42);
        final TestPlayer winner = new TestPlayer(winnerSpawn.clone().add(1, 0, 1));
        final TestPlayer loser = new TestPlayer(loserSpawn.clone().add(-1, 0, -1));
        final List<CompletableFuture<Void>> resets = new ArrayList<>();
        final ArenaLease lease;
        final Duel duel;
        RoundEndState round;
        int nextTask;

        Fixture() throws Exception {
            when(world.getName()).thenReturn("arenas");
            when(world.getPlayers()).thenReturn(List.of(winner.player, loser.player));
            when(server.plugin.getArenaManager()).thenReturn(arenas);
            when(server.plugin.getLobbyManager()).thenReturn(lobby);
            when(server.plugin.getLogger()).thenReturn(mock(Logger.class));
            bukkit.when(() -> Bukkit.getWorld("arenas")).thenReturn(world);
            bukkit.when(() -> Bukkit.getPlayer(winner.id)).thenReturn(winner.player);
            bukkit.when(() -> Bukkit.getPlayer(loser.id)).thenReturn(loser.player);
            ArenaMap map = spy(new ArenaMap("arena"));
            map.setWorldName("arenas");
            map.setRollbackRegion(32, 60, 32, 47, 80, 47);
            doReturn(winnerSpawn).when(map).getTeam1Spawn();
            doReturn(loserSpawn).when(map).getTeam2Spawn();
            doReturn(new Location(world, 38, 70, 38)).when(map).getSpectatorSpawn();
            ArenaInstance instance = new ArenaInstance(0, map, new BlockBounds(0, -64, 0, 1023, 319, 1023));
            var reuse = ArenaInstance.class.getDeclaredMethod("reuseSavedCopy");
            reuse.setAccessible(true);
            reuse.invoke(instance);
            lease = instance.acquire();
            var setup = new DuelSetup(winner.id);
            setup.setSelectedMap(map);
            setup.setSelectedKit(new Kit("sword"));
            setup.getTeam1().addPlayer(winner.id);
            setup.getTeam2().addPlayer(loser.id);
            setup.setRounds(3);
            duel = spy(new Duel(server.plugin, manager, setup, lease, true));
            doNothing().when(duel).prepareRound();
            for (TestPlayer participant : List.of(winner, loser)) {
                when(manager.getDuel(participant.id)).thenReturn(duel);
                when(manager.getDuel(participant.player)).thenReturn(duel);
            }
            when(arenas.resetRound(eq(lease), anySet(), any())).thenAnswer(call -> {
                Consumer<Player> protect = call.getArgument(2);
                protect.accept(winner.player);
                protect.accept(loser.player);
                assertFalse(winner.gravity);
                assertFalse(loser.gravity);
                CompletableFuture<Void> reset = new CompletableFuture<>();
                resets.add(reset);
                return reset;
            });
        }

        void begin() {
            round = new RoundEndState(duel, duel.getTeam1());
            duel.setState(round);
            runNext();
            assertEquals(1, resets.size());
            assertEquals(1, duel.getTeam1().getScore());
        }

        void assertWaitingWithoutLosingTheResult(int roundNumber) {
            assertSame(round, duel.getState());
            assertTrue(round.isEnabled());
            assertFalse(duel.isFinished());
            assertEquals(roundNumber, duel.getCurrentRound());
            assertEquals(1, duel.getTeam1().getScore());
            assertEquals(0, duel.getTeam2().getScore());
            assertTrue(lease.instance().owns(lease));
            assertFalse(winner.gravity);
            assertFalse(loser.gravity);
            verify(arenas, never()).discard(any());
            verify(arenas, never()).abandon(any());
            verify(manager, never()).endDuel(any());
            verify(duel, never()).broadcastCountdown(anyInt());
            verify(duel, never()).prepareRound();
        }

        CompletableFuture<Void> latestReset() { return resets.getLast(); }
        List<StateTestServer.Scheduled> remainingOneShots() {
            return server.scheduled.subList(nextTask, server.scheduled.size()).stream().filter(task -> !task.repeating()).toList();
        }
        void runNext() {
            while (nextTask < server.scheduled.size()) {
                var task = server.scheduled.get(nextTask++);
                if (!task.repeating()) { task.run(); return; }
            }
            fail("No pending one-shot task");
        }
        @Override public void close() {
            duel.getState().disable();
            avatars.close();
            bukkit.close();
            server.close();
        }
    }

    private static final class TestPlayer {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        Location location;
        boolean gravity = true, blockTeleport, dead;

        TestPlayer(Location start) {
            location = start;
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Contestant");
            when(player.isOnline()).thenReturn(true);
            when(player.isDead()).thenAnswer(call -> dead);
            when(player.getWorld()).thenAnswer(call -> location.getWorld());
            when(player.getLocation()).thenAnswer(call -> location.clone());
            when(player.hasGravity()).thenAnswer(call -> gravity);
            doAnswer(call -> { gravity = call.getArgument(0); return null; }).when(player).setGravity(anyBoolean());
            when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
            when(player.teleport(any(Location.class))).thenAnswer(call -> {
                if (blockTeleport) return false;
                location = ((Location) call.getArgument(0)).clone();
                return true;
            });
        }
    }
}
