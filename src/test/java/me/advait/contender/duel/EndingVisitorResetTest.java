package me.advait.contender.duel;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EndingVisitorResetTest {
    @Test void finalResetProtectsUnregisteredVisitorsAcrossTheFullCellAndPreservesTheirState() {
        try (Fixture f = new Fixture()) {
            Guest director = f.guest(new Location(f.world, 10, 200, 10));
            Guest outside = f.guest(new Location(f.world, 120, 65, 120));
            director.gravity = false;
            f.begin();

            verify(f.duel).returnParticipantsToLobby();
            verify(f.duel, never()).rollbackArena(any());
            verify(f.duel).rollbackRoundArena(any(), any());
            assertFalse(director.gravity);
            assertFalse(director.data.isEmpty());
            assertTrue(outside.gravity);
            assertTrue(outside.data.isEmpty());
            verify(director.player, never()).teleport(any(Location.class));

            f.complete.run();

            assertEquals(f.spawn, director.location);
            assertFalse(director.gravity, "The director originally had gravity disabled");
            assertTrue(director.data.isEmpty());
            verify(director.player, never()).setGameMode(any());
            verify(director.player, never()).getInventory();
            verify(outside.player, never()).teleport(any(Location.class));
            verify(f.duel).finish();
            verify(f.duel, never()).resetFailed(any());
        }
    }

    @Test void visitorsCanLookButCannotMoveOrTakeDamageWhilePasteRuns() {
        try (Fixture f = new Fixture()) {
            Guest guest = f.guest(new Location(f.world, 35, 65, 35));
            f.begin();
            Location moved = guest.location.clone().add(2, -1, 0);
            moved.setYaw(80); moved.setPitch(25);
            var move = new PlayerMoveEvent(guest.player, guest.location.clone(), moved);
            f.state.resetMove(move);
            assertEquals(guest.location.toVector(), move.getTo().toVector());
            assertEquals(80, move.getTo().getYaw()); assertEquals(25, move.getTo().getPitch());
            var teleport = new PlayerTeleportEvent(guest.player, guest.location.clone(), f.spawn.clone());
            f.state.resetTeleport(teleport);
            assertTrue(teleport.isCancelled());
            var damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(guest.player);
            f.state.resetDamage(damage);
            verify(damage).setCancelled(true);
            guest.gravity = true;
            guest.velocity = new Vector(1, -1, 1);
            f.repeat();
            assertFalse(guest.gravity);
            assertEquals(new Vector(), guest.velocity);
        }
    }

    @Test void teleportingOutReleasesProtectionAndNeverPullsTheVisitorBack() {
        try (Fixture f = new Fixture()) {
            Guest guest = f.guest(new Location(f.world, 35, 65, 35));
            f.begin();
            Location away = new Location(f.world, 150, 65, 150);
            var exit = new PlayerTeleportEvent(guest.player, guest.location.clone(), away);
            f.state.resetTeleport(exit);
            assertFalse(exit.isCancelled());
            f.state.resetTeleported(exit);
            guest.location = away;
            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            f.repeat();
            f.complete.run();
            verify(guest.player, never()).teleport(any(Location.class));
            assertEquals(away, guest.location);
            verify(f.duel).finish();
        }
    }

    @Test void prePasteCallbackProtectsVisitorsWhoArriveAfterTheInitialScan() {
        try (Fixture f = new Fixture()) {
            f.begin();
            Guest late = f.guest(new Location(f.world, 35, 90, 35));
            f.protect.accept(late.player);
            assertFalse(late.gravity);
            assertFalse(late.data.isEmpty());
            when(f.map.getSpectatorSpawn()).thenReturn(null);
            f.complete.run();
            assertEquals(f.teamSpawn, late.location);
            assertTrue(late.gravity);
            assertTrue(late.data.isEmpty());
            verify(f.duel).finish();
        }
    }

    @Test void aCancelledExitDoesNotReleaseTheVisitorInsideThePaste() {
        try (Fixture f = new Fixture()) {
            Guest guest = f.guest(new Location(f.world, 35, 65, 35));
            f.begin();
            var exit = new PlayerTeleportEvent(guest.player, guest.location.clone(), new Location(f.world, 150, 65, 150));
            exit.setCancelled(true);
            f.state.resetTeleported(exit);
            assertFalse(guest.gravity);
            assertFalse(guest.data.isEmpty());
        }
    }

    @Test void aBlockedVisitorReturnCannotDiscardTheCompletedMatchOrRestoredCopy() {
        try (Fixture f = new Fixture()) {
            Guest guest = f.guest(new Location(f.world, 35, 65, 35));
            doReturn(false).when(guest.player).teleport(any(Location.class));
            f.begin();
            f.complete.run();
            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            verify(f.duel).finish();
            verify(f.duel, never()).resetFailed(any());
        }
    }

    @Test void disablingRestoresVisitorsAndLateCallbacksCannotFinishOrRefreezeThem() {
        try (Fixture f = new Fixture()) {
            Guest guest = f.guest(new Location(f.world, 35, 65, 35));
            f.begin();
            f.state.disable();
            assertTrue(guest.gravity);
            assertTrue(guest.data.isEmpty());
            clearInvocations(guest.player);
            f.complete.run();
            f.repeat();
            assertThrows(IllegalStateException.class, () -> f.protect.accept(guest.player));
            verify(guest.player, never()).setGravity(anyBoolean());
            verify(guest.player, never()).teleport(any(Location.class));
            verify(f.duel, never()).finish();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Duel duel = mock(Duel.class);
        final World world = mock(World.class);
        final ArenaMap map = mock(ArenaMap.class);
        final Location spawn = new Location(world, 38, 75, 38);
        final Location teamSpawn = new Location(world, 34, 65, 34);
        final List<Guest> guests = new ArrayList<>();
        final EndingState state;
        Consumer<Player> protect;
        Runnable complete;

        Fixture() {
            when(world.getName()).thenReturn("arenas");
            when(world.getPlayers()).thenAnswer(call -> guests.stream().map(guest -> guest.player).toList());
            bukkit.when(() -> Bukkit.getWorld("arenas")).thenReturn(world);
            when(map.getWorldName()).thenReturn("arenas");
            when(map.getSpectatorSpawn()).thenReturn(spawn);
            when(map.getTeam1Spawn()).thenReturn(teamSpawn);
            when(duel.getMap()).thenReturn(map);
            var instance = new ArenaInstance(0, map, new BlockBounds(0, -64, 0, 99, 319, 99));
            when(duel.getArena()).thenReturn(new ArenaLease(instance, UUID.randomUUID()));
            when(duel.getPlugin()).thenReturn(server.plugin);
            Logger logger = mock(Logger.class);
            when(server.plugin.getLogger()).thenReturn(logger);
            state = new EndingState(duel, true);
            when(duel.getState()).thenReturn(state);
            doAnswer(call -> { protect = call.getArgument(0); complete = call.getArgument(1); return null; })
                    .when(duel).rollbackRoundArena(any(), any());
            doAnswer(call -> { state.disable(); return null; }).when(duel).finish();
            doAnswer(call -> { state.disable(); return null; }).when(duel).resetFailed(any());
        }

        Guest guest(Location location) {
            Guest guest = new Guest(location);
            guests.add(guest);
            when(guest.player.getUniqueId()).thenReturn(guest.id);
            when(guest.player.getName()).thenReturn("Director");
            when(guest.player.isOnline()).thenReturn(true);
            when(guest.player.getWorld()).thenAnswer(call -> guest.location.getWorld());
            when(guest.player.getLocation()).thenAnswer(call -> guest.location.clone());
            when(guest.player.hasGravity()).thenAnswer(call -> guest.gravity);
            doAnswer(call -> { guest.gravity = call.getArgument(0); return null; }).when(guest.player).setGravity(anyBoolean());
            doAnswer(call -> { guest.velocity = ((Vector) call.getArgument(0)).clone(); return null; }).when(guest.player).setVelocity(any());
            when(guest.player.getPersistentDataContainer()).thenReturn(guest.pdc);
            when(guest.pdc.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(call -> guest.data.get(call.getArgument(0)));
            doAnswer(call -> { guest.data.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(guest.pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));
            doAnswer(call -> { guest.data.remove(call.getArgument(0)); return null; }).when(guest.pdc).remove(any());
            when(guest.player.teleport(any(Location.class))).thenAnswer(call -> {
                Location destination = call.getArgument(0);
                var event = new PlayerTeleportEvent(guest.player, guest.location.clone(), destination.clone());
                state.resetTeleport(event);
                if (event.isCancelled()) return false;
                state.resetTeleported(event);
                guest.location = event.getTo().clone();
                return true;
            });
            return guest;
        }

        void begin() { state.enable(); }
        void repeat() { List.copyOf(server.scheduled).forEach(StateTestServer.Scheduled::run); }
        @Override public void close() { state.disable(); bukkit.close(); server.close(); }
    }

    private static final class Guest {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, Byte> data = new HashMap<>();
        Location location;
        boolean gravity = true;
        Vector velocity = new Vector();
        Guest(Location location) { this.location = location.clone(); }
    }
}
