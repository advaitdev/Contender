package me.advait.contender.duel;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.spectator.ArenaProtectionListener;
import me.advait.contender.spectator.SpectatorVisibility;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuelRoundResetTest {
    @Test void everyParticipantLeavesBeforeResetAndReturnsBeforeCountdownWithoutLosingInventory() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.duel.isSpectator(f.loser.id));
            assertTrue(f.duel.isSpectator(f.observer.id));

            f.begin();

            verify(f.arenas).reset(f.lease);
            assertTrue(f.players.values().stream().allMatch(player -> player.location.equals(f.holding)));
            assertFalse(f.round.canAddSpectator());
            verify(f.duel, never()).broadcastCountdown(anyInt());
            assertEquals(1, f.server.scheduled.size(), "No countdown until the arena reset completes");
            for (var player : f.players.values()) verifyNoInteractions(player.inventory);
            verify(f.lobby, never()).sendToLobby(any());

            f.completeReset();

            assertEquals(f.team1Spawn, f.winner.location);
            assertEquals(f.team2Spawn, f.loser.location);
            assertEquals(f.spectatorSpawn, f.observer.location);
            assertTrue(f.round.canAddSpectator());
            for (var player : f.players.values()) {
                verifyNoInteractions(player.inventory);
                verify(player.player, never()).setGameMode(any());
            }
            assertEquals(List.of(20L, 40L, 60L, 80L), f.server.scheduled.subList(1, 5).stream()
                    .map(StateTestServer.Scheduled::delay).toList());
            for (int seconds = 3; seconds >= 1; seconds--) {
                f.runNext();
                verify(f.duel).broadcastCountdown(seconds);
                assertEquals(f.team1Spawn, f.winner.location);
            }
            f.runNext();
            assertInstanceOf(ActiveState.class, f.duel.getState());
            verify(f.duel).prepareRound();
        }
    }

    @Test void aBlockedHoldingTeleportStopsTheDuelBeforeAnyResetCanRun() throws Exception {
        try (Fixture f = new Fixture()) {
            f.winner.blockTeleport = true;

            f.begin();

            verify(f.arenas, never()).reset(any());
            verify(f.duel).resetFailed(argThat(failure -> failure.getMessage().contains("Could not move")));
            assertTrue(f.duel.isFinished());
            verify(f.arenas).discard(f.lease);
            verify(f.duel, never()).broadcastCountdown(anyInt());
        }
    }

    @Test void heldPlayersCanLookAroundButCannotWalkOrTeleportAway() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            Location moved = f.holding.clone().add(2, 0, 0);
            moved.setYaw(60); moved.setPitch(20);
            var movement = new PlayerMoveEvent(f.winner.player, f.holding.clone(), moved);

            f.protection.onMove(movement);

            assertEquals(f.holding.toVector(), movement.getTo().toVector());
            assertEquals(60, movement.getTo().getYaw());
            assertEquals(20, movement.getTo().getPitch());
            var command = new PlayerTeleportEvent(f.winner.player, f.holding.clone(), moved,
                    PlayerTeleportEvent.TeleportCause.COMMAND);
            f.protection.onTeleport(command);
            assertTrue(command.isCancelled());
            var pluginTeleport = new PlayerTeleportEvent(f.winner.player, f.holding.clone(), moved,
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            f.protection.onTeleport(pluginTeleport);
            assertTrue(pluginTeleport.isCancelled(), "Only the round's scoped teleport can leave holding");
            var arenaEntry = new PlayerTeleportEvent(f.winner.player, f.holding.clone(), f.team1Spawn,
                    PlayerTeleportEvent.TeleportCause.COMMAND);
            f.protection.onTeleport(arenaEntry);
            assertTrue(arenaEntry.isCancelled());
        }
    }

    @Test void realDeathsRespawnOutsideTheArenaAndDelayResetUntilEvacuationFinishes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loser.dead = true;

            f.begin();

            verify(f.arenas, never()).reset(any());
            assertEquals(f.holding, f.winner.location);
            assertEquals(f.holding, f.observer.location);
            var respawn = new PlayerRespawnEvent(f.loser.player, f.team2Spawn.clone(), false);
            f.round.onRespawn(respawn);
            assertEquals(f.holding, respawn.getRespawnLocation());
            f.loser.location = respawn.getRespawnLocation().clone();
            f.loser.dead = false;

            f.runNext();

            verify(f.arenas).reset(f.lease);
            assertEquals(f.holding, f.loser.location);
            f.completeReset();
            assertEquals(f.team2Spawn, f.loser.location);
            assertFalse(f.duel.isFinished());
        }
    }

    @Test void lateResetCompletionAfterForceCancellationCannotReturnPlayersOrStartARound() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            List<StateTestServer.Scheduled> pending = List.copyOf(f.server.scheduled);

            f.duel.forceCancel();
            f.players.values().forEach(player -> clearInvocations(player.player));
            f.completeReset();
            pending.forEach(StateTestServer.Scheduled::run);

            assertTrue(f.duel.isFinished());
            assertFalse(f.round.isEnabled());
            assertTrue(f.players.values().stream().allMatch(player -> player.location.equals(f.holding)));
            for (var player : f.players.values()) verify(player.player, never()).teleport(any(Location.class));
            verify(f.duel, never()).broadcastCountdown(anyInt());
            verify(f.duel, never()).prepareRound();
            verify(f.arenas).abandon(f.lease);
            assertEquals(1, f.server.scheduled.size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<SpectatorVisibility> avatars = mockConstruction(SpectatorVisibility.class);
        final World world = mock(World.class);
        final World lobbyWorld = mock(World.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final LobbyManager lobby = mock(LobbyManager.class);
        final DuelManager manager = mock(DuelManager.class);
        final Map<UUID, TestPlayer> players = new LinkedHashMap<>();
        final Location team1Spawn = new Location(world, 34, 65, 34);
        final Location team2Spawn = new Location(world, 42, 65, 42);
        final Location spectatorSpawn = new Location(world, 38, 70, 38);
        final Location holding = new Location(lobbyWorld, 2, 65, 2);
        final ArenaLease lease;
        final Duel duel;
        final ArenaProtectionListener protection;
        final TestPlayer winner;
        final TestPlayer loser;
        final TestPlayer observer;
        final CompletableFuture<Void> reset = new CompletableFuture<>();
        final AtomicBoolean pasting = new AtomicBoolean();
        RoundEndState round;
        int nextTask;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            when(world.getName()).thenReturn("arenas");
            when(lobbyWorld.getName()).thenReturn("lobby");
            when(server.plugin.getArenaManager()).thenReturn(arenas);
            when(server.plugin.getLobbyManager()).thenReturn(lobby);
            when(server.plugin.getLogger()).thenReturn(mock(Logger.class));
            when(lobby.getLobbyLocation()).thenReturn(holding);
            when(arenas.isArenaWorld(world)).thenReturn(true);
            when(arenas.isPreparingEntry(any(), any())).thenAnswer(call -> {
                Location from = call.getArgument(0), to = call.getArgument(1);
                return pasting.get() && to.getWorld() == world
                        && (from.getWorld() != world || !insideSlot(from)) && insideSlot(to);
            });
            bukkit.when(() -> Bukkit.getWorld("arenas")).thenReturn(world);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> {
                TestPlayer player = players.get(call.getArgument(0));
                return player == null ? null : player.player;
            });
            winner = player("Winner", team1Spawn);
            loser = player("Loser", team2Spawn);
            observer = player("Observer", spectatorSpawn);

            ArenaMap map = spy(new ArenaMap("arena"));
            map.setWorldName("arenas");
            map.setRollbackRegion(32, 60, 32, 47, 80, 47);
            doReturn(team1Spawn).when(map).getTeam1Spawn();
            doReturn(team2Spawn).when(map).getTeam2Spawn();
            doReturn(spectatorSpawn).when(map).getSpectatorSpawn();
            ArenaInstance instance = new ArenaInstance(0, map, new BlockBounds(0, -64, 0, 1023, 319, 1023));
            var reuse = ArenaInstance.class.getDeclaredMethod("reuseSavedCopy");
            reuse.setAccessible(true); reuse.invoke(instance);
            lease = instance.acquire();
            assertNotNull(lease);
            var setup = new DuelSetup(winner.id);
            setup.setSelectedMap(map);
            setup.setSelectedKit(new Kit("sword"));
            setup.getTeam1().addPlayer(winner.id);
            setup.getTeam2().addPlayer(loser.id);
            setup.setRounds(3);
            duel = spy(new Duel(server.plugin, manager, setup, lease, true));
            doNothing().when(duel).prepareRound();
            for (TestPlayer player : players.values()) {
                when(manager.getDuel(player.player)).thenReturn(duel);
                when(manager.getDuel(player.id)).thenReturn(duel);
            }
            protection = new ArenaProtectionListener(manager, arenas);
            duel.addSpectator(observer.id);
            var deceased = Duel.class.getDeclaredField("deadPlayerSpectators");
            deceased.setAccessible(true);
            ((Set<UUID>) deceased.get(duel)).add(loser.id);
            duel.getTeam2().markDead(loser.id);
            when(arenas.reset(lease)).thenAnswer(call -> {
                for (TestPlayer player : players.values()) {
                    assertFalse(player.location.getWorld() == world && insideSlot(player.location),
                            player.player.getName() + " must leave the slot before reset");
                }
                pasting.set(true);
                return reset;
            });
            doAnswer(call -> {
                Player player = call.getArgument(0);
                players.get(player.getUniqueId()).location = holding.clone();
                return null;
            }).when(lobby).sendToLobby(any());
            for (TestPlayer player : players.values()) clearInvocations(player.player, player.inventory);
        }

        private boolean insideSlot(Location location) { return location.getX() >= 0 && location.getX() < 1024 && location.getZ() >= 0 && location.getZ() < 1024; }

        private TestPlayer player(String name, Location spawn) {
            TestPlayer state = new TestPlayer(spawn);
            players.put(state.id, state);
            when(state.player.getUniqueId()).thenReturn(state.id);
            when(state.player.getName()).thenReturn(name);
            when(state.player.isOnline()).thenReturn(true);
            when(state.player.isDead()).thenAnswer(ignored -> state.dead);
            when(state.player.getWorld()).thenAnswer(ignored -> state.location.getWorld());
            when(state.player.getLocation()).thenAnswer(ignored -> state.location.clone());
            when(state.player.getInventory()).thenReturn(state.inventory);
            when(state.player.teleport(any(Location.class))).thenAnswer(call -> {
                Location target = call.getArgument(0);
                var event = new PlayerTeleportEvent(state.player, state.location.clone(), target.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (state.blockTeleport) event.setCancelled(true);
                protection.onTeleport(event);
                if (event.isCancelled()) return false;
                state.location = event.getTo().clone();
                return true;
            });
            return state;
        }

        void begin() {
            round = new RoundEndState(duel, duel.getTeam1());
            duel.setState(round);
            verify(arenas, never()).reset(any());
            runNext();
        }

        void runNext() { server.scheduled.get(nextTask++).run(); }
        void completeReset() { pasting.set(false); reset.complete(null); }

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
        final PlayerInventory inventory = mock(PlayerInventory.class);
        Location location;
        boolean dead;
        boolean blockTeleport;
        TestPlayer(Location location) { this.location = location.clone(); }
    }
}
