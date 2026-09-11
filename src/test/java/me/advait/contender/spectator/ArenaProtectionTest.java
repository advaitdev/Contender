package me.advait.contender.spectator;

import me.advait.contender.arena.*;
import me.advait.contender.duel.*;
import me.advait.contender.map.ArenaMap;
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
    private Block block(World world, int x) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, x, 64, 0));
        return block;
    }
}
