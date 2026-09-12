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
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuelRoundResetTest {
    @Test void occupantsStayInPlaceForThePasteAndReachTheirSpawnsBeforeCountdown() throws Exception {
        try (Fixture f = new Fixture()) {
            f.winner.location.add(1, 0, 1);
            f.loser.location.add(-1, 0, -1);
            f.observer.location.add(0, 2, 0);
            f.observer.gravity = false;
            Map<UUID, Location> original = f.locations();
            f.begin();
            Set<UUID> participants = f.duel.getAllParticipants();
            verify(f.arenas).resetRound(eq(f.lease), eq(participants), any());
            verify(f.arenas, never()).reset(any());
            assertEquals(original, f.locations());
            assertFalse(f.round.canAddSpectator());
            verify(f.duel, never()).broadcastCountdown(anyInt());
            verifyNoInteractions(f.lobby);
            for (var player : f.players.values()) {
                verify(player.player, never()).teleport(any(Location.class));
                verifyNoInteractions(player.inventory);
                assertFalse(player.gravity);
                assertFalse(player.data.isEmpty(), "Temporary gravity must survive a crash");
            }
            assertTrue(f.countdownTasks().isEmpty());
            f.completeReset();
            assertEquals(f.team1Spawn, f.winner.location);
            assertEquals(f.team2Spawn, f.loser.location);
            assertEquals(f.spectatorSpawn, f.observer.location);
            assertTrue(f.round.canAddSpectator());
            assertTrue(f.winner.gravity); assertTrue(f.loser.gravity);
            assertFalse(f.observer.gravity, "Restore the original setting, not always true");
            assertTrue(f.players.values().stream().allMatch(player -> player.data.isEmpty()));
            for (var player : f.players.values()) {
                verifyNoInteractions(player.inventory);
                verify(player.player, never()).setGameMode(any());
            }
            verifyNoInteractions(f.lobby);
            assertEquals(List.of(20L, 40L, 60L, 80L), f.countdownTasks().stream().map(StateTestServer.Scheduled::delay).toList());
            for (int seconds = 3; seconds >= 1; seconds--) {
                f.runNext(); verify(f.duel).broadcastCountdown(seconds);
            }
            f.runNext();
            assertInstanceOf(ActiveState.class, f.duel.getState());
            verify(f.duel).prepareRound();
            assertTrue(f.winner.gravity);
        }
    }

    @Test void blockedSpawnTeleportFailsOnlyAfterPasteAndReleasesResetProtection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.winner.blockTeleport = true;
            f.begin();
            verify(f.arenas).resetRound(eq(f.lease), anySet(), any());
            verify(f.duel, never()).resetFailed(any());
            f.completeReset();
            verify(f.duel).resetFailed(argThat(failure -> failure.getMessage().contains("Could not move")));
            assertTrue(f.duel.isFinished());
            assertEquals(DuelResult.Reason.CANCELLED, f.duel.getResult().reason());
            verify(f.arenas).discard(f.lease);
            verify(f.manager).endDuel(f.duel);
            verify(f.duel, never()).broadcastCountdown(anyInt());
            assertTrue(f.players.values().stream().allMatch(player -> player.gravity && player.data.isEmpty()));
        }
    }

    @Test void resetFreezesMovementAndVelocityInTheArenaWithoutPreventingLooking() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            Location from = f.winner.location.clone();
            Location moved = from.clone().add(2, 0, 0);
            moved.setYaw(60); moved.setPitch(20);
            var movement = new PlayerMoveEvent(f.winner.player, from, moved);
            f.containment.onMove(movement);
            assertEquals(from.toVector(), movement.getTo().toVector());
            assertEquals(60, movement.getTo().getYaw()); assertEquals(20, movement.getTo().getPitch());
            for (var cause : List.of(PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                var teleport = new PlayerTeleportEvent(f.winner.player, from.clone(), f.lobbySpawn, cause);
                f.containment.onTeleport(teleport);
                assertTrue(teleport.isCancelled(), "An unscoped teleport must not escape reset protection");
            }
            f.winner.gravity = true;
            f.winner.velocity = new Vector(1, -2, 1);
            f.repeatFreeze();
            assertFalse(f.winner.gravity);
            assertEquals(new Vector(), f.winner.velocity);
            verify(f.winner.player, atLeastOnce()).setFallDistance(0);
            assertEquals(from, f.winner.location);
        }
    }

    @Test void nativeDeathDoesNotDelayPastingButTheCountdownWaitsForRespawn() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loser.dead = true;
            f.begin();
            verify(f.arenas).resetRound(eq(f.lease), anySet(), any());
            assertEquals(f.team2Spawn, f.loser.location);
            verifyNoInteractions(f.lobby);
            f.completeReset();
            assertFalse(f.round.canAddSpectator());
            assertTrue(f.countdownTasks().isEmpty());
            verify(f.duel, never()).broadcastCountdown(anyInt());
            verify(f.loser.player, never()).teleport(any(Location.class));
            var respawn = new PlayerRespawnEvent(f.loser.player, f.lobbySpawn.clone(), false);
            f.round.onRespawn(respawn);
            assertEquals(f.team2Spawn, respawn.getRespawnLocation());
            f.loser.location = respawn.getRespawnLocation().clone();
            f.loser.dead = false;
            f.loser.gravity = true;
            f.repeatFreeze();
            assertFalse(f.loser.gravity);
            f.runNext();
            assertTrue(f.round.canAddSpectator());
            assertTrue(f.loser.gravity); assertTrue(f.loser.data.isEmpty());
            assertFalse(f.countdownTasks().isEmpty());
            assertFalse(f.duel.isFinished());
            verifyNoInteractions(f.lobby);
            verify(f.arenas, times(1)).resetRound(eq(f.lease), anySet(), any());
        }
    }

    @Test void respawningDuringThePasteStaysInTheArenaAndKeepsProtection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loser.dead = true;
            f.begin();
            var respawn = new PlayerRespawnEvent(f.loser.player, f.lobbySpawn.clone(), false);
            f.round.onRespawn(respawn);
            assertEquals(f.team2Spawn, respawn.getRespawnLocation());
            f.loser.location = respawn.getRespawnLocation().clone();
            f.loser.dead = false; f.loser.gravity = true;
            f.runNext();
            assertFalse(f.loser.gravity);
            verify(f.duel).applyDeadSpectatorMode(f.loser.player);
            assertTrue(f.pasting.get()); assertTrue(f.countdownTasks().isEmpty());
            f.completeReset();
            assertTrue(f.loser.gravity); assertTrue(f.loser.data.isEmpty());
            verifyNoInteractions(f.lobby);
        }
    }

    @Test void spectatorsWhoLeaveOrDisconnectRecoverGravityBeforeTheResetFinishes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            assertFalse(f.observer.gravity);
            f.duel.removeSpectator(f.observer.player);
            f.repeatFreeze();
            assertTrue(f.observer.gravity); assertTrue(f.observer.data.isEmpty());
            assertEquals(f.lobbySpawn, f.observer.location);
            clearInvocations(f.observer.player);
            f.completeReset();
            verify(f.observer.player, never()).teleport(any(Location.class));
        }
        try (Fixture f = new Fixture()) {
            f.begin();
            f.observer.online = false;
            f.duel.disconnectSpectator(f.observer.player);
            f.round.releaseOnQuit(new PlayerQuitEvent(f.observer.player, Component.empty()));
            f.repeatFreeze();
            assertTrue(f.observer.gravity); assertTrue(f.observer.data.isEmpty());
            assertFalse(f.duel.isFinished());
            f.completeReset();
            assertTrue(f.observer.gravity);
        }
    }

    @Test void forceCancellationRestoresGravityAndLateCallbacksCannotMoveAnyoneBack() throws Exception {
        try (Fixture f = new Fixture()) {
            f.observer.gravity = false;
            f.begin();
            List<StateTestServer.Scheduled> pending = List.copyOf(f.server.scheduled);
            f.duel.forceCancel();
            assertTrue(f.winner.gravity); assertTrue(f.loser.gravity); assertFalse(f.observer.gravity);
            assertTrue(f.players.values().stream().allMatch(player -> player.data.isEmpty()));
            f.players.values().forEach(player -> clearInvocations(player.player));
            f.completeReset();
            pending.forEach(StateTestServer.Scheduled::run);
            assertTrue(f.duel.isFinished()); assertFalse(f.round.isEnabled());
            assertTrue(f.players.values().stream().allMatch(player -> player.location.equals(f.lobbySpawn)));
            for (var player : f.players.values()) {
                verify(player.player, never()).teleport(any(Location.class));
                verify(player.player, never()).setGravity(anyBoolean());
            }
            verify(f.duel, never()).broadcastCountdown(anyInt());
            verify(f.duel, never()).prepareRound();
            verify(f.arenas).abandon(f.lease);
            assertTrue(f.countdownTasks().isEmpty());
        }
    }

    @Test void aFailedPasteReleasesProtectionAndNeverStartsTheNextRound() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            var failure = new IllegalStateException("Paste failed");
            f.pasting.set(false);
            f.reset.completeExceptionally(failure);
            List.copyOf(f.server.scheduled).forEach(StateTestServer.Scheduled::run);
            verify(f.duel).resetFailed(failure);
            assertTrue(f.duel.isFinished());
            assertEquals(DuelResult.Reason.CANCELLED, f.duel.getResult().reason());
            assertTrue(f.players.values().stream().allMatch(player -> player.gravity && player.data.isEmpty()));
            verify(f.arenas).discard(f.lease);
            verify(f.manager).endDuel(f.duel);
            verify(f.duel, never()).broadcastCountdown(anyInt());
            verify(f.duel, never()).prepareRound();
        }
    }

    @Test void anUnregisteredVisitorCanStayForResetWithoutJoiningTheDuelRoster() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = f.player("Guest", new Location(f.world, 39, 65, 36));
            var outside = f.player("Outside", new Location(f.world, 2000, 65, 2000));
            when(guest.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            Set<UUID> participants = Set.copyOf(f.duel.getAllParticipants());
            Location guestStart = guest.location.clone();

            f.begin();

            assertFalse(guest.gravity);
            assertEquals(guestStart, guest.location);
            assertTrue(outside.gravity, "Another arena's visitor must not be frozen");
            assertFalse(f.duel.hasParticipant(guest.id));
            assertEquals(participants, f.duel.getAllParticipants());
            verify(f.duel, never()).resetFailed(any());
            verify(f.arenas).resetRound(eq(f.lease), eq(participants), any());
            verifyNoInteractions(guest.inventory, outside.inventory, f.lobby);

            f.completeReset();

            assertEquals(f.spectatorSpawn, guest.location);
            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            assertFalse(f.duel.hasParticipant(guest.id));
            assertEquals(participants, f.duel.getAllParticipants());
            assertFalse(f.duel.isFinished());
            assertFalse(f.countdownTasks().isEmpty());
            verify(guest.player, never()).setGameMode(any());
            verifyNoInteractions(guest.inventory, outside.inventory, f.lobby);
            verify(outside.player, never()).teleport(any(Location.class));
            verify(outside.player, never()).setGravity(anyBoolean());
        }
    }

    @Test void aVisitorWhoArrivesAfterResetWasQueuedIsProtectedByThePasteCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.begin();
            var guest = f.player("LateGuest", new Location(f.world, 38, 66, 38));
            assertTrue(guest.gravity);

            f.protectOccupant.accept(guest.player);

            assertFalse(guest.gravity, "Protect a visitor found by the final pre-paste occupancy scan");
            assertFalse(guest.data.isEmpty());
            assertFalse(f.duel.hasParticipant(guest.id));
            f.completeReset();
            assertEquals(f.spectatorSpawn, guest.location);
            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            assertFalse(f.duel.isFinished());
            verify(f.duel, never()).resetFailed(any());
            verify(guest.player, never()).setGameMode(any());
            verifyNoInteractions(guest.inventory, f.lobby);
        }
    }

    @Test void visitorMovementIsHeldInPlaceWithoutBlockingLookingAround() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = f.player("Guest", new Location(f.world, 38, 66, 38));
            f.begin();
            var from = guest.location.clone();
            var destination = from.clone().add(3, -1, 2);
            destination.setYaw(90);
            destination.setPitch(-15);
            var movement = new PlayerMoveEvent(guest.player, from, destination);

            f.round.onVisitorMove(movement);

            assertEquals(from.toVector(), movement.getTo().toVector());
            assertEquals(90, movement.getTo().getYaw());
            assertEquals(-15, movement.getTo().getPitch());
            var withinArena = new PlayerTeleportEvent(guest.player, from.clone(), from.clone().add(2, 0, 0),
                    PlayerTeleportEvent.TeleportCause.COMMAND);
            f.round.onVisitorTeleporting(withinArena);
            assertTrue(withinArena.isCancelled(), "A visitor cannot move elsewhere inside the area being pasted");
            guest.gravity = true;
            guest.velocity = new Vector(1, -2, 1);
            f.repeatFreeze();
            assertFalse(guest.gravity);
            assertEquals(new Vector(), guest.velocity);
            assertFalse(f.duel.hasParticipant(guest.id));
        }
    }

    @Test void successfulVisitorTeleportOutReleasesGravityAndDoesNotPullThemBack() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = f.player("Guest", new Location(f.world, 38, 66, 38));
            f.begin();
            var blocked = new PlayerTeleportEvent(guest.player, guest.location.clone(), f.lobbySpawn.clone(),
                    PlayerTeleportEvent.TeleportCause.COMMAND);
            blocked.setCancelled(true);
            f.round.onVisitorTeleport(blocked);
            assertFalse(guest.gravity, "A blocked teleport must leave reset protection active");

            var exit = new PlayerTeleportEvent(guest.player, guest.location.clone(), f.lobbySpawn.clone(),
                    PlayerTeleportEvent.TeleportCause.COMMAND);
            f.containment.onTeleport(exit);
            f.round.onVisitorTeleporting(exit);
            assertFalse(exit.isCancelled());
            guest.location = exit.getTo().clone();
            f.round.onVisitorTeleport(exit);

            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            f.repeatFreeze();
            clearInvocations(guest.player);
            f.completeReset();

            assertEquals(f.lobbySpawn, guest.location);
            assertTrue(guest.gravity);
            verify(guest.player, never()).teleport(any(Location.class));
            verify(guest.player, never()).setGravity(anyBoolean());
            verifyNoInteractions(guest.inventory);
        }
    }

    @Test void aFailedPasteRestoresVisitorPhysicsWithoutChangingTheirInventoryOrMode() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = f.player("Guest", new Location(f.world, 38, 66, 38));
            var alreadyFloating = f.player("FloatingGuest", new Location(f.world, 37, 68, 37));
            alreadyFloating.gravity = false;
            f.begin();
            f.pasting.set(false);

            f.reset.completeExceptionally(new IllegalStateException("Paste failed"));

            assertTrue(f.duel.isFinished());
            assertTrue(guest.gravity);
            assertFalse(alreadyFloating.gravity);
            assertTrue(guest.data.isEmpty());
            assertTrue(alreadyFloating.data.isEmpty());
            assertFalse(f.duel.hasParticipant(guest.id));
            verify(guest.player, never()).setGameMode(any());
            verify(alreadyFloating.player, never()).setGameMode(any());
            verifyNoInteractions(guest.inventory, alreadyFloating.inventory);
        }
    }

    @Test void cancellingRestoresVisitorsAndLateCallbacksCannotTouchThem() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = f.player("Guest", new Location(f.world, 38, 66, 38));
            f.begin();
            var pending = List.copyOf(f.server.scheduled);

            f.duel.forceCancel();

            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            assertFalse(f.duel.hasParticipant(guest.id));
            clearInvocations(guest.player);
            assertThrows(IllegalStateException.class, () -> f.protectOccupant.accept(guest.player));
            f.completeReset();
            pending.forEach(StateTestServer.Scheduled::run);

            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            verify(guest.player, never()).setGravity(anyBoolean());
            verify(guest.player, never()).teleport(any(Location.class));
            verify(guest.player, never()).setGameMode(any());
            verifyNoInteractions(guest.inventory);
            assertTrue(f.duel.isFinished());
        }
    }

    @Test void visitorsAreProtectedFromDamageOnlyWhileTheArenaIsResetting() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = f.player("Guest", new Location(f.world, 38, 66, 38));
            var outside = f.player("Outside", new Location(f.world, 2000, 66, 2000));
            f.begin();
            var damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(guest.player);
            var unrelated = mock(EntityDamageEvent.class);
            when(unrelated.getEntity()).thenReturn(outside.player);

            f.round.onVisitorDamage(damage);
            f.round.onVisitorDamage(unrelated);

            verify(damage).setCancelled(true);
            verify(unrelated, never()).setCancelled(anyBoolean());
            f.completeReset();
            clearInvocations(damage);
            f.round.onVisitorDamage(damage);
            verify(damage, never()).setCancelled(anyBoolean());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<SpectatorVisibility> avatars = mockConstruction(SpectatorVisibility.class);
        final World world = mock(World.class), lobbyWorld = mock(World.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final LobbyManager lobby = mock(LobbyManager.class);
        final DuelManager manager = mock(DuelManager.class);
        final Map<UUID, TestPlayer> players = new LinkedHashMap<>();
        final Location team1Spawn = new Location(world, 34, 65, 34);
        final Location team2Spawn = new Location(world, 42, 65, 42);
        final Location spectatorSpawn = new Location(world, 38, 70, 38);
        final Location lobbySpawn = new Location(lobbyWorld, 2, 65, 2);
        final ArenaLease lease;
        final Duel duel;
        final ArenaProtectionListener containment;
        final TestPlayer winner, loser, observer;
        final CompletableFuture<Void> reset = new CompletableFuture<>();
        final AtomicBoolean pasting = new AtomicBoolean();
        Consumer<Player> protectOccupant;
        RoundEndState round;
        int nextTask;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            when(world.getName()).thenReturn("arenas"); when(world.getEntities()).thenReturn(List.of());
            when(world.getPlayers()).thenAnswer(call -> players.values().stream()
                    .filter(player -> player.online && player.location.getWorld() == world)
                    .map(player -> player.player).toList());
            when(lobbyWorld.getName()).thenReturn("lobby");
            when(server.plugin.getArenaManager()).thenReturn(arenas);
            when(server.plugin.getLobbyManager()).thenReturn(lobby);
            when(server.plugin.getLogger()).thenReturn(mock(Logger.class));
            when(lobby.getLobbyLocation()).thenReturn(lobbySpawn);
            when(arenas.isArenaWorld(world)).thenReturn(true);
            when(arenas.isPreparingEntry(any(), any())).thenAnswer(call -> {
                Location from = call.getArgument(0), to = call.getArgument(1);
                return pasting.get() && to.getWorld() == world
                        && (from.getWorld() != world || !insideSlot(from)) && insideSlot(to);
            });
            bukkit.when(() -> Bukkit.getWorld("arenas")).thenReturn(world);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> {
                TestPlayer player = players.get(call.getArgument(0));
                return player == null || !player.online ? null : player.player;
            });
            winner = player("Winner", team1Spawn); loser = player("Loser", team2Spawn); observer = player("Observer", spectatorSpawn);
            ArenaMap map = spy(new ArenaMap("arena"));
            map.setWorldName("arenas"); map.setRollbackRegion(32, 60, 32, 47, 80, 47);
            doReturn(team1Spawn).when(map).getTeam1Spawn(); doReturn(team2Spawn).when(map).getTeam2Spawn();
            doReturn(spectatorSpawn).when(map).getSpectatorSpawn();
            ArenaInstance instance = new ArenaInstance(0, map, new BlockBounds(0, -64, 0, 1023, 319, 1023));
            var reuse = ArenaInstance.class.getDeclaredMethod("reuseSavedCopy"); reuse.setAccessible(true); reuse.invoke(instance);
            lease = instance.acquire(); assertNotNull(lease);
            var setup = new DuelSetup(winner.id); setup.setSelectedMap(map); setup.setSelectedKit(new Kit("sword"));
            setup.getTeam1().addPlayer(winner.id); setup.getTeam2().addPlayer(loser.id); setup.setRounds(3);
            duel = spy(new Duel(server.plugin, manager, setup, lease, true)); doNothing().when(duel).prepareRound();
            for (TestPlayer player : players.values()) {
                when(manager.getDuel(player.player)).thenReturn(duel); when(manager.getDuel(player.id)).thenReturn(duel);
            }
            containment = new ArenaProtectionListener(manager, arenas);
            duel.addSpectator(observer.id);
            var deceased = Duel.class.getDeclaredField("deadPlayerSpectators"); deceased.setAccessible(true);
            ((Set<UUID>) deceased.get(duel)).add(loser.id); duel.getTeam2().markDead(loser.id);
            when(arenas.resetRound(eq(lease), anySet(), any())).thenAnswer(call -> {
                assertEquals(duel.getAllParticipants(), call.getArgument(1));
                protectOccupant = call.getArgument(2);
                for (TestPlayer player : players.values()) {
                    if (!player.online || player.location.getWorld() != world || !insideSlot(player.location)) continue;
                    protectOccupant.accept(player.player);
                    assertFalse(player.gravity, "Freeze each occupant before starting the paste");
                }
                pasting.set(true); return reset;
            });
            doAnswer(call -> {
                Player player = call.getArgument(0); players.get(player.getUniqueId()).location = lobbySpawn.clone(); return null;
            }).when(lobby).sendToLobby(any());
            for (TestPlayer player : players.values()) clearInvocations(player.player, player.inventory);
            clearInvocations(lobby);
        }

        private boolean insideSlot(Location location) { return location.getX() >= 0 && location.getX() < 1024 && location.getZ() >= 0 && location.getZ() < 1024; }
        private TestPlayer player(String name, Location spawn) {
            TestPlayer state = new TestPlayer(spawn); players.put(state.id, state);
            when(state.player.getUniqueId()).thenReturn(state.id); when(state.player.getName()).thenReturn(name);
            when(state.player.isOnline()).thenAnswer(ignored -> state.online); when(state.player.isDead()).thenAnswer(ignored -> state.dead);
            when(state.player.hasGravity()).thenAnswer(ignored -> state.gravity);
            doAnswer(call -> { state.gravity = call.getArgument(0); return null; }).when(state.player).setGravity(anyBoolean());
            doAnswer(call -> { state.velocity = ((Vector) call.getArgument(0)).clone(); return null; }).when(state.player).setVelocity(any());
            when(state.player.getWorld()).thenAnswer(ignored -> state.location.getWorld());
            when(state.player.getLocation()).thenAnswer(ignored -> state.location.clone());
            when(state.player.getInventory()).thenReturn(state.inventory); when(state.player.getPersistentDataContainer()).thenReturn(state.pdc);
            when(state.pdc.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(call -> state.data.get(call.getArgument(0)));
            doAnswer(call -> { state.data.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(state.pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));
            doAnswer(call -> { state.data.remove(call.getArgument(0)); return null; }).when(state.pdc).remove(any());
            when(state.player.teleport(any(Location.class))).thenAnswer(call -> {
                Location target = call.getArgument(0);
                var event = new PlayerTeleportEvent(state.player, state.location.clone(), target.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (state.blockTeleport) event.setCancelled(true);
                containment.onTeleport(event);
                if (round != null && round.isEnabled()) round.onVisitorTeleporting(event);
                if (event.isCancelled()) return false;
                state.location = event.getTo().clone();
                if (round != null && round.isEnabled()) round.onVisitorTeleport(event);
                return true;
            });
            return state;
        }
        Map<UUID, Location> locations() {
            Map<UUID, Location> snapshot = new LinkedHashMap<>(); players.forEach((id, player) -> snapshot.put(id, player.location.clone())); return snapshot;
        }
        List<StateTestServer.Scheduled> countdownTasks() {
            return server.scheduled.stream().filter(task -> !task.repeating() && task.delay() >= 20).toList();
        }
        void begin() {
            round = new RoundEndState(duel, duel.getTeam1()); duel.setState(round);
            verify(arenas, never()).resetRound(any(), anySet(), any()); verify(arenas, never()).reset(any()); runNext();
        }
        void runNext() {
            while (nextTask < server.scheduled.size()) {
                var task = server.scheduled.get(nextTask++);
                if (!task.repeating()) { task.run(); return; }
            }
            fail("No pending one-shot task");
        }
        void repeatFreeze() { server.scheduled.stream().filter(StateTestServer.Scheduled::repeating).findFirst().orElseThrow().run(); }
        void completeReset() { pasting.set(false); reset.complete(null); }
        @Override public void close() {
            duel.getState().disable(); avatars.close(); bukkit.close(); server.close();
        }
    }

    private static final class TestPlayer {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, Byte> data = new HashMap<>();
        Location location;
        Vector velocity = new Vector();
        boolean gravity = true, online = true, dead, blockTeleport;
        TestPlayer(Location location) { this.location = location.clone(); }
    }
}
