package me.advait.contender.spectator;

import me.advait.contender.arena.*;
import me.advait.contender.duel.*;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.kit.Kit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.entity.Mannequin;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaProtectionTest {
    @Test void aHoldingStateCanAuthorizeTransportWithoutOrdinaryArenaContainmentRewritingIt() {
        var f = new MovementFixture();
        World lobby = mock(World.class);
        Location waiting = new Location(lobby, 0, 70, 0);
        when(f.state.handleArenaContainment(any())).thenReturn(true);
        var transport = new PlayerTeleportEvent(f.player, f.spawn, waiting, PlayerTeleportEvent.TeleportCause.PLUGIN);

        f.listener.onTeleport(transport);

        assertEquals(waiting, transport.getTo());
        assertFalse(transport.isCancelled());
        verify(f.state).handleArenaContainment(transport);
    }

    @Test void statesThatDoNotHandleContainmentKeepTheNormalArenaBoundary() {
        var f = new MovementFixture();
        World lobby = mock(World.class);
        var teleport = new PlayerTeleportEvent(f.player, f.spawn, new Location(lobby, 0, 70, 0), PlayerTeleportEvent.TeleportCause.COMMAND);

        f.listener.onTeleport(teleport);

        verify(f.state).handleArenaContainment(teleport);
        assertEquals(f.spawn, teleport.getTo());
        assertFalse(teleport.isCancelled());
    }

    @Test void holdingStateCannotBypassTheGuardAgainstEnteringAnArenaDuringAPaste() {
        var f = new MovementFixture();
        World lobby = mock(World.class);
        Location waiting = new Location(lobby, 0, 70, 0);
        when(f.state.handleArenaContainment(any())).thenReturn(true);
        when(f.arenas.isPreparingEntry(waiting, f.spawn)).thenReturn(true);
        var transport = new PlayerTeleportEvent(f.player, waiting, f.spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);

        f.listener.onTeleport(transport);

        assertTrue(transport.isCancelled());
        verify(f.state, never()).handleArenaContainment(any());
    }

    @Test void holdingStateCanBlockWalkingWithoutTheArenaBoundaryMovingPlayersBackInside() {
        var f = new MovementFixture();
        World lobby = mock(World.class);
        Location waiting = new Location(lobby, 0, 70, 0);
        var movement = new PlayerMoveEvent(f.player, waiting, waiting.clone().add(1, 0, 0));
        doAnswer(call -> {
            ((PlayerMoveEvent) call.getArgument(0)).setTo(waiting);
            return true;
        }).when(f.state).handleArenaContainment(any());

        f.listener.onMove(movement);

        assertEquals(waiting, movement.getTo());
    }

    @Test void directorsAndOtherPlayersCannotEnterAnArenaWhileItIsBeingPrepared() {
        World world = mock(World.class);
        Player director = mock(Player.class);
        Location from = new Location(world, 0, 70, 0), to = new Location(world, 32, 70, 32);
        ArenaManager arenas = mock(ArenaManager.class);
        when(arenas.isPreparingEntry(from, to)).thenReturn(true);
        var listener = new ArenaProtectionListener(mock(DuelManager.class), arenas);
        var walk = new PlayerMoveEvent(director, from, to);
        listener.onMove(walk);
        assertTrue(walk.isCancelled());
        var teleport = new PlayerTeleportEvent(director, from, to, PlayerTeleportEvent.TeleportCause.COMMAND);
        listener.onTeleport(teleport);
        assertTrue(teleport.isCancelled());
        var leave = new PlayerTeleportEvent(director, to, from, PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onTeleport(leave);
        assertFalse(leave.isCancelled());
    }

    @Test void idleTemplatesAreProtectedWhileReservedDeathMannequinsCanAnimate() {
        World world = mock(World.class);
        ArenaManager arenas = mock(ArenaManager.class);
        when(arenas.isArenaWorld(world)).thenReturn(true);
        ArenaInstance instance = mock(ArenaInstance.class);
        when(arenas.at(any())).thenReturn(instance);
        Mannequin mannequin = mock(Mannequin.class);
        when(mannequin.getWorld()).thenReturn(world);
        when(mannequin.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        ArenaProtectionListener listener = new ArenaProtectionListener(mock(DuelManager.class), arenas);
        EntityDamageEvent idleDamage = mock(EntityDamageEvent.class);
        when(idleDamage.getEntity()).thenReturn(mannequin);
        listener.onEntityDamage(idleDamage);
        verify(idleDamage).setCancelled(true);
        when(instance.isReserved()).thenReturn(true);
        EntityDamageEvent deathAnimation = mock(EntityDamageEvent.class);
        when(deathAnimation.getEntity()).thenReturn(mannequin);
        listener.onEntityDamage(deathAnimation);
        verify(deathAnimation, never()).setCancelled(anyBoolean());
    }
    @Test void fluidsAndExplosionsStayInsideTheMatchThatCreatedThem() {
        World world = mock(World.class);
        ArenaManager arenas = mock(ArenaManager.class);
        DuelManager duels = mock(DuelManager.class);
        when(arenas.isArenaWorld(world)).thenReturn(true);
        ArenaInstance first = mock(ArenaInstance.class);
        ArenaInstance second = mock(ArenaInstance.class);
        when(first.status()).thenReturn(ArenaInstance.Status.IN_USE);
        when(second.status()).thenReturn(ArenaInstance.Status.IN_USE);
        when(arenas.at(any())).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return location.getX() < 10 ? first : second;
        });
        Duel duel1 = mock(Duel.class), duel2 = mock(Duel.class);
        when(duel1.getArena()).thenReturn(new ArenaLease(first, UUID.randomUUID()));
        when(duel2.getArena()).thenReturn(new ArenaLease(second, UUID.randomUUID()));
        when(duel1.isCombatActive()).thenReturn(true);
        when(duel2.isCombatActive()).thenReturn(true);
        when(duels.getActiveDuels()).thenReturn(List.of(duel1, duel2));
        Block source = block(world, 8), inside = block(world, 9), outside = block(world, 10);
        ArenaProtectionListener listener = new ArenaProtectionListener(duels, arenas);
        BlockFromToEvent flow = mock(BlockFromToEvent.class);
        when(flow.getBlock()).thenReturn(source);
        when(flow.getToBlock()).thenReturn(outside);
        listener.onFlow(flow);
        verify(flow).setCancelled(true);
        BlockFromToEvent local = mock(BlockFromToEvent.class);
        when(local.getBlock()).thenReturn(source);
        when(local.getToBlock()).thenReturn(inside);
        listener.onFlow(local);
        verify(local, never()).setCancelled(anyBoolean());
        EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
        when(explosion.getLocation()).thenReturn(new Location(world, 8, 64, 0));
        List<Block> affected = new ArrayList<>(List.of(source, inside, outside));
        when(explosion.blockList()).thenReturn(affected);
        listener.onEntityExplode(explosion);
        assertEquals(List.of(source, inside), affected);
    }
    @Test void sumoKnockbackCrossesEveryPlatformEdgeBeforeTheFallCountsAsALoss() {
        double[][] edges = {{10.8, 0, 11.4, 0}, {-9.8, 0, -10.4, 0},
                {0, 10.8, 0, 11.4}, {0, -9.8, 0, -10.4}};
        for (double[] edge : edges) {
            var f = new MovementFixture();
            Location from = new Location(f.world, edge[0], 62, edge[1]);
            Location outside = new Location(f.world, edge[2], 61.8, edge[3]);
            var knockback = new PlayerMoveEvent(f.player, from, outside);
            f.listener.onMove(knockback);
            assertEquals(outside, knockback.getTo());
            assertFalse(knockback.isCancelled());
            Location bottom = new Location(f.world, edge[2], 60, edge[3]);
            var descent = new PlayerMoveEvent(f.player, outside, bottom);
            f.listener.onMove(descent);
            assertEquals(bottom, descent.getTo());
            verify(f.duel, never()).handleDeath(any(), any());
            var fall = new PlayerMoveEvent(f.player, bottom, bottom.clone().subtract(0, 0.1, 0));
            f.listener.onMove(fall);
            verify(f.duel).handleDeath(f.player, null);
            assertEquals(f.spawn, fall.getTo());
        }
    }

    @Test void combatMovementHasNoTemplateCeilingAndOrdinaryKitsCanReachTheWorldVoid() {
        var f = new MovementFixture();
        var jump = new PlayerMoveEvent(f.player, new Location(f.world, 0, 80.8, 0), new Location(f.world, 0, 81.2, 0));
        Location above = jump.getTo().clone();
        f.listener.onMove(jump);
        assertEquals(above, jump.getTo());
        f.kit.setPvpHurt(false); f.kit.setPveHurt(false);
        Location from = new Location(f.world, 12, 62, 0);
        for (double y : new double[]{60, 59.9, -64, -130}) {
            Location to = new Location(f.world, 12, y, 0);
            var fall = new PlayerMoveEvent(f.player, from, to);
            f.listener.onMove(fall);
            assertEquals(to, fall.getTo());
            assertFalse(fall.isCancelled());
            from = to;
        }
        verify(f.duel, never()).handleDeath(any(), any()); // ActiveState handles actual void damage.
    }

    @Test void contestantsCannotCrossIntoAnotherArenaCell() {
        var f = new MovementFixture();
        f.kit.setPvpHurt(false); f.kit.setPveHurt(false);
        Location from = new Location(f.world, -41.8, 50, -41.8);
        var fall = new PlayerMoveEvent(f.player, from, new Location(f.world, -42.2, 49.8, -42.2, 30, 45));
        f.listener.onMove(fall);
        assertEquals(f.spawn, fall.getTo());
        assertFalse(fall.isCancelled());
        // Paper fires a teleport when a move listener changes the destination.
        var correction = new PlayerTeleportEvent(f.player, from, fall.getTo(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        f.listener.onTeleport(correction);
        assertEquals(f.spawn, correction.getTo());
        verify(f.duel, never()).handleDeath(any(), any());
    }

    @Test void spectatorsCountdownsAndTeleportsKeepTheirContainment() {
        var f = new MovementFixture();
        var teleport = new PlayerTeleportEvent(f.player, f.spawn, new Location(f.world, 12, 59, 0));
        f.listener.onTeleport(teleport);
        assertEquals(f.spawn, teleport.getTo());
        World other = mock(World.class); when(other.getName()).thenReturn("another_arena");
        var worldChange = new PlayerMoveEvent(f.player, f.spawn, new Location(other, 0, 59, 0));
        f.listener.onMove(worldChange);
        assertEquals(f.spawn, worldChange.getTo());
        when(f.duel.isSpectator(f.player.getUniqueId())).thenReturn(true);
        var spectator = new PlayerMoveEvent(f.player, f.spawn, new Location(f.world, 12, 59, 0));
        f.listener.onMove(spectator);
        assertEquals(f.spawn, spectator.getTo());
        when(f.duel.isSpectator(f.player.getUniqueId())).thenReturn(false);
        when(f.duel.isCombatActive()).thenReturn(false);
        var countdown = new PlayerMoveEvent(f.player, f.spawn, new Location(f.world, 12, 59, 0));
        f.listener.onMove(countdown);
        assertEquals(f.spawn, countdown.getTo());
        verify(f.duel, never()).handleDeath(any(), any());
    }

    private static final class MovementFixture {
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final Duel duel = mock(Duel.class);
        final Kit kit = new Kit("sumo");
        final AbstractDuelState state = mock(AbstractDuelState.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final Location spawn = new Location(world, 0, 65, 0);
        final ArenaProtectionListener listener;

        MovementFixture() {
            when(world.getName()).thenReturn("arena");
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            var duels = mock(DuelManager.class);
            when(duels.getDuel(player)).thenReturn(duel);
            when(duel.getState()).thenReturn(state); when(state.isEnabled()).thenReturn(true);
            when(duel.isCombatActive()).thenReturn(true);
            var map = spy(new ArenaMap("sumo"));
            map.setWorldName("arena"); map.setRollbackRegion(-10, 60, -10, 10, 80, 10);
            doReturn(spawn).when(map).getTeam1Spawn();
            doReturn(spawn).when(map).getTeam2Spawn();
            doReturn(spawn).when(map).getSpectatorSpawn();
            when(duel.getMap()).thenReturn(map);
            var instance = new ArenaInstance(0, map, new BlockBounds(-42, -64, -42, 981, 319, 981));
            when(duel.getArena()).thenReturn(new ArenaLease(instance, UUID.randomUUID()));
            when(duel.getKit()).thenReturn(kit); kit.setPvpHurt(true); kit.setPveHurt(true);
            listener = new ArenaProtectionListener(duels, arenas);
        }
    }
    private Block block(World world, int x) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, x, 64, 0));
        return block;
    }
}
