package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.kit.Kit;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.role.RoleManager;
import me.advait.contender.vote.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuelManagerTransactionsTest {
    @Test void blockedFirstSpectateDoesNotRegisterOrClearTheVisitor() {
        try (Fixture f = new Fixture()) {
            Duel target = f.start();
            f.visitor.blockTeleport = true;
            assertThrows(IllegalStateException.class, () -> f.manager.spectate(f.visitor.player, target));
            assertNull(f.manager.getDuel(f.visitor.player));
            verify(target, never()).attachSpectator(any());
            verify(f.visitor.inventory, never()).clear();
            assertFalse(f.manager.isSpectatorTransfer(f.teleportEvent(f.visitor, f.spawn)));
        }
    }

    @Test void failedSwitchKeepsTheOldMatchAndAvatarUntilATeleportActuallySucceeds() {
        try (Fixture f = new Fixture()) {
            Duel previous = f.start(), target = f.start();
            f.manager.spectate(f.visitor.player, previous);
            clearInvocations(previous, target, f.visitor.inventory);
            f.visitor.blockTeleport = true;
            f.teleportObserver = event -> {
                assertSame(previous, f.manager.getDuel(f.visitor.player));
                assertTrue(f.manager.isSpectatorTransfer(event));
                var unrelated = new PlayerTeleportEvent(f.visitor.player, event.getFrom(), event.getTo().clone().add(1, 0, 0));
                assertFalse(f.manager.isSpectatorTransfer(unrelated));
            };

            assertThrows(IllegalStateException.class, () -> f.manager.spectate(f.visitor.player, target));

            assertSame(previous, f.manager.getDuel(f.visitor.player));
            assertTrue(previous.isSpectator(f.visitor.id));
            assertFalse(target.isSpectator(f.visitor.id));
            verify(previous, never()).disconnectSpectator(any());
            verify(target, never()).attachSpectator(any());
            verify(f.visitor.inventory, never()).clear();
            assertFalse(f.manager.isSpectatorTransfer(f.teleportEvent(f.visitor, f.spawn)));
        }
    }

    @Test void aReportedSuccessfulTeleportToTheWrongPlaceCannotCommitMembership() {
        try (Fixture f = new Fixture()) {
            Duel target = f.start();
            f.visitor.redirect = f.lobbySpawn.clone();
            assertThrows(IllegalStateException.class, () -> f.manager.spectate(f.visitor.player, target));
            assertNull(f.manager.getDuel(f.visitor.player));
            verify(target, never()).attachSpectator(any());
            verify(f.visitor.inventory, never()).clear();
        }
    }

    @Test void successfulSwitchCommitsOnlyAfterMovingAndDoesNotVisitTheLobby() {
        try (Fixture f = new Fixture()) {
            Duel previous = f.start(), target = f.start();
            f.manager.spectate(f.visitor.player, previous);
            clearInvocations(previous, target, f.visitor.inventory, f.lobby);
            f.teleportObserver = event -> {
                assertSame(previous, f.manager.getDuel(f.visitor.player));
                assertTrue(previous.isSpectator(f.visitor.id));
                verify(previous, never()).disconnectSpectator(any());
                verify(f.visitor.inventory, never()).clear();
            };

            f.manager.spectate(f.visitor.player, target);

            assertSame(target, f.manager.getDuel(f.visitor.player));
            assertFalse(previous.isSpectator(f.visitor.id));
            assertTrue(target.isSpectator(f.visitor.id));
            verify(previous).disconnectSpectator(f.visitor.player);
            verify(target).attachSpectator(f.visitor.player);
            verify(f.visitor.inventory).clear();
            verifyNoInteractions(f.lobby);
        }
    }

    @Test void blockedLobbyReturnKeepsTheSpectatorRegisteredAndTheirAvatarIntact() {
        try (Fixture f = new Fixture()) {
            Duel previous = f.start();
            f.manager.spectate(f.visitor.player, previous);
            clearInvocations(previous, f.roles, f.visitor.inventory);
            f.visitor.blockTeleport = true;
            f.teleportObserver = event -> assertSame(previous, f.manager.getDuel(f.visitor.player));

            assertThrows(IllegalStateException.class, () -> f.manager.leaveSpectating(f.visitor.player));

            assertSame(previous, f.manager.getDuel(f.visitor.player));
            assertTrue(previous.isSpectator(f.visitor.id));
            verify(previous, never()).disconnectSpectator(any());
            verify(f.roles, never()).applySpectatorRole(any());
            verify(f.visitor.inventory, never()).clear();
        }
    }

    @Test void successfulLobbyReturnReleasesMembershipAfterTheCheckedTeleport() {
        try (Fixture f = new Fixture()) {
            Duel previous = f.start();
            f.manager.spectate(f.visitor.player, previous);
            f.teleportObserver = event -> {
                assertSame(previous, f.manager.getDuel(f.visitor.player));
                assertTrue(previous.isSpectator(f.visitor.id));
                assertTrue(f.manager.isSpectatorTransfer(event));
            };

            f.manager.leaveSpectating(f.visitor.player);

            assertNull(f.manager.getDuel(f.visitor.player));
            assertFalse(previous.isSpectator(f.visitor.id));
            assertEquals(f.lobbySpawn, f.visitor.location);
            verify(previous).disconnectSpectator(f.visitor.player);
            verify(f.roles).applySpectatorRole(f.visitor.player);
            assertFalse(f.manager.isSpectatorTransfer(f.teleportEvent(f.visitor, f.lobbySpawn)));
        }
    }

    @Test void decidedResultIsReportedBeforeCleanupWithoutReleasingPlayersOrTheArena() {
        try (Fixture f = new Fixture()) {
            Duel duel = f.start();
            var result = new DuelResult(DuelResult.Reason.FINISHED, 2, 1, 1);
            when(duel.getResult()).thenReturn(result);
            f.manager.reportResult(duel);
            assertEquals(List.of(result), f.results);
            assertTrue(f.manager.getActiveDuels().contains(duel));
            assertTrue(duel.getAllDuelPlayers().stream().allMatch(f.manager::isPlaying));
            verify(f.arenas, never()).release(duel.getArena());
            f.manager.reportResult(duel);
            f.manager.endDuel(duel);
            assertEquals(List.of(result), f.results);
            verify(f.arenas).release(duel.getArena());
        }
    }

    @Test void cancellationIsOnlyReportedAfterTheDuelActuallyLeavesTheManager() {
        try (Fixture f = new Fixture()) {
            Duel duel = f.start();
            f.manager.reportResult(duel);
            assertTrue(f.results.isEmpty());
            f.manager.endDuel(duel);
            assertEquals(List.of(new DuelResult(DuelResult.Reason.CANCELLED, 0, 0, null)), f.results);
            f.manager.reportResult(duel);
            assertEquals(1, f.results.size());
        }
    }

    @Test void shutdownFlushesDecidedForfeitsButDoesNotTurnInterruptedMatchesIntoResults() {
        try (Fixture f = new Fixture()) {
            Duel decided = f.start(), interrupted = f.start();
            var result = new DuelResult(DuelResult.Reason.FORFEIT, 1, 0, 1);
            when(decided.getResult()).thenReturn(result);
            doAnswer(call -> { f.manager.endDuel(decided); return null; }).when(decided).shutdown();
            doAnswer(call -> { f.manager.endDuel(interrupted); return null; }).when(interrupted).shutdown();

            f.manager.shutdown();

            assertEquals(List.of(result), f.results);
            assertTrue(f.manager.getActiveDuels().isEmpty());
        }
    }

    @Test void failedPersistenceRetriesAfterCleanupAndDoesNotReportTheResultTwice() {
        try (Fixture f = new Fixture()) {
            AtomicInteger attempts = new AtomicInteger();
            Duel duel = f.start(result -> {
                if (attempts.incrementAndGet() < 3) throw new IllegalStateException("Disk temporarily unavailable");
                f.results.add(result);
            });
            var result = new DuelResult(DuelResult.Reason.FINISHED, 2, 1, 1);
            when(duel.getResult()).thenReturn(result);
            assertDoesNotThrow(() -> f.manager.reportResult(duel));
            assertDoesNotThrow(() -> f.manager.endDuel(duel));
            assertTrue(f.manager.getActiveDuels().isEmpty());
            assertTrue(f.results.isEmpty());
            assertEquals(1, f.retries.size(), "Keep only one retry task per pending result");

            f.retries.getFirst().run();
            f.manager.reportResult(duel);

            assertEquals(3, attempts.get());
            assertEquals(List.of(result), f.results);
            assertEquals(1, f.retries.size());
        }
    }

    @Test void resultCallbacksCannotReenterTheSameDelivery() {
        try (Fixture f = new Fixture()) {
            Duel[] reported = new Duel[1];
            reported[0] = f.start(result -> {
                f.manager.reportResult(reported[0]);
                f.results.add(result);
            });
            var result = new DuelResult(DuelResult.Reason.FINISHED, 2, 0, 1);
            when(reported[0].getResult()).thenReturn(result);
            f.manager.reportResult(reported[0]);
            assertEquals(List.of(result), f.results);
        }
    }

    @Test void forceCancelCannotOverwriteAPreviouslyDecidedResultWaitingForStorage() {
        try (Fixture f = new Fixture()) {
            AtomicBoolean writable = new AtomicBoolean();
            Duel duel = f.start(result -> {
                if (!writable.get()) throw new IllegalStateException("Disk temporarily unavailable");
                f.results.add(result);
            });
            var result = new DuelResult(DuelResult.Reason.FINISHED, 2, 1, 1);
            when(duel.getResult()).thenReturn(result);
            f.manager.reportResult(duel);
            doAnswer(call -> {
                when(duel.getResult()).thenReturn(new DuelResult(DuelResult.Reason.CANCELLED, 2, 1, 1));
                return null;
            }).when(duel).forceCancel();

            f.manager.forceCancelAll();
            writable.set(true);
            f.retries.getFirst().run();

            assertTrue(f.manager.getActiveDuels().isEmpty());
            assertEquals(List.of(result), f.results);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Contender plugin = mock(Contender.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final RoleManager roles = mock(RoleManager.class);
        final LobbyManager lobby = mock(LobbyManager.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Logger logger = mock(Logger.class);
        final List<Runnable> retries = new ArrayList<>();
        final World world = mock(World.class);
        final Location spawn = new Location(world, 35, 75, 35), lobbySpawn = new Location(world, 2000, 65, 2000);
        final ArenaMap map = mock(ArenaMap.class);
        final Kit kit = mock(Kit.class);
        final Map<UUID, TestPlayer> players = new HashMap<>();
        final List<DuelResult> results = new ArrayList<>();
        final DuelManager manager = new DuelManager(plugin);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<Duel> construction;
        final TestPlayer visitor;
        Consumer<PlayerTeleportEvent> teleportObserver = event -> { };

        Fixture() {
            when(plugin.getArenaManager()).thenReturn(arenas);
            when(plugin.getRoleManager()).thenReturn(roles);
            when(plugin.getLobbyManager()).thenReturn(lobby);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(100L))).thenAnswer(call -> {
                retries.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
            VoteManager votes = mock(VoteManager.class);
            when(plugin.getVoteManager()).thenReturn(votes);
            when(roles.isContestant(any())).thenReturn(true);
            when(map.getId()).thenReturn("arena");
            when(map.getSpectatorSpawn()).thenReturn(spawn);
            when(lobby.getLobbyLocation()).thenReturn(lobbySpawn);
            when(arenas.acquire("arena")).thenAnswer(call -> new ArenaLease(mock(ArenaInstance.class), UUID.randomUUID()));
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> {
                TestPlayer player = players.get(call.getArgument(0)); return player == null ? null : player.player;
            });
            construction = mockConstruction(Duel.class, (duel, context) -> {
                DuelSetup setup = (DuelSetup) context.arguments().get(2);
                Set<UUID> contestants = setup.getAllPlayers();
                Set<UUID> spectators = new HashSet<>();
                when(duel.getAllDuelPlayers()).thenReturn(contestants);
                when(duel.getAllParticipants()).thenAnswer(call -> {
                    Set<UUID> all = new HashSet<>(contestants); all.addAll(spectators); return all;
                });
                when(duel.isInDuel(any())).thenAnswer(call -> contestants.contains(call.getArgument(0)));
                when(duel.isSpectator(any())).thenAnswer(call -> spectators.contains(call.getArgument(0)));
                when(duel.getArena()).thenReturn((ArenaLease) context.arguments().get(3));
                when(duel.getMap()).thenReturn(map);
                when(duel.getResult()).thenReturn(new DuelResult(DuelResult.Reason.CANCELLED, 0, 0, null));
                AbstractDuelState state = mock(AbstractDuelState.class);
                when(state.canAddSpectator()).thenReturn(true);
                when(duel.getState()).thenReturn(state);
                doAnswer(call -> { spectators.add(((Player) call.getArgument(0)).getUniqueId()); return null; }).when(duel).attachSpectator(any());
                doAnswer(call -> { spectators.remove(((Player) call.getArgument(0)).getUniqueId()); return null; }).when(duel).disconnectSpectator(any());
            });
            visitor = player();
            when(lobby.sendToLobbyChecked(visitor.player)).thenAnswer(call -> visitor.player.teleport(lobbySpawn.clone()));
        }

        Duel start() { return start(results::add); }
        Duel start(Consumer<DuelResult> completion) {
            TestPlayer first = player(), second = player();
            DuelSetup setup = new DuelSetup(UUID.randomUUID());
            setup.setSelectedMap(map); setup.setSelectedKit(kit);
            setup.getTeam1().addPlayer(first.id); setup.getTeam2().addPlayer(second.id);
            return manager.startDuel(setup, completion);
        }

        PlayerTeleportEvent teleportEvent(TestPlayer player, Location destination) {
            return new PlayerTeleportEvent(player.player, player.location.clone(), destination.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        private TestPlayer player() {
            TestPlayer state = new TestPlayer(lobbySpawn);
            players.put(state.id, state);
            when(state.player.getUniqueId()).thenReturn(state.id);
            when(state.player.getName()).thenReturn("Player");
            when(state.player.getInventory()).thenReturn(state.inventory);
            when(state.player.getWorld()).thenAnswer(call -> state.location.getWorld());
            when(state.player.getLocation()).thenAnswer(call -> state.location.clone());
            when(state.player.teleport(any(Location.class))).thenAnswer(call -> {
                Location destination = call.getArgument(0);
                var event = teleportEvent(state, destination);
                teleportObserver.accept(event);
                if (state.blockTeleport) return false;
                state.location = state.redirect == null ? destination.clone() : state.redirect.clone();
                return true;
            });
            return state;
        }

        @Override public void close() { construction.close(); bukkit.close(); }
    }

    private static final class TestPlayer {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        Location location, redirect;
        boolean blockTeleport;
        TestPlayer(Location location) { this.location = location.clone(); }
    }
}
