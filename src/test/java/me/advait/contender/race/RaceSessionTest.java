package me.advait.contender.race;

import me.advait.contender.arena.*;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.testutil.StateTestServer;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.*;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.PlayerInventory;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceSessionTest {
    @Test void returnsToLastCheckpointAndRejoinKeepsProgressWhileCleanupInvalidatesCallbacks() {
        try (var env = new StateTestServer(); var access = mockStatic(RegistryAccess.class); var bukkit = mockStatic(Bukkit.class); var kit = mockStatic(RaceKit.class)) {
            access.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
            var attributes = new HashMap<net.kyori.adventure.key.Key, org.bukkit.attribute.Attribute>();
            doAnswer(call -> attributes.computeIfAbsent(call.getArgument(0), key -> {
                var attribute = mock(org.bukkit.attribute.Attribute.class);
                when(attribute.getKey()).thenReturn(new NamespacedKey(key.namespace(), key.value())); return attribute;
            })).when(Registry.ATTRIBUTE).getOrThrow(any(net.kyori.adventure.key.Key.class));
            var manager = mock(RaceManager.class); var world = mock(World.class);
            when(world.getName()).thenReturn("arena"); bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            var chunk = mock(Chunk.class); when(chunk.addPluginChunkTicket(env.plugin)).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            when(world.getChunkAt(0, 0)).thenReturn(chunk);
            LivingEntity first = mob("#1", world, 2), second = mob("#2", world, 4), finish = mob("Finish", world, 6);
            when(chunk.getEntities()).thenReturn(new Entity[]{first, second, finish});
            ArenaMap map = map(); var arena = mock(ArenaInstance.class); when(arena.map()).thenReturn(map);
            when(arena.cell()).thenReturn(new me.advait.contender.map.BlockBounds(0, -64, 0, 100, 320, 100));
            Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
            when(player.getLocation()).thenAnswer(i -> new Location(world, 1, 64, 1));
            when(player.getBoundingBox()).thenAnswer(i -> new org.bukkit.util.BoundingBox(.7, 64, .7, 1.3, 65.8, 1.3));
            when(player.isOnline()).thenReturn(true); when(player.getMaxHealth()).thenReturn(20d);
            when(player.getActivePotionEffects()).thenReturn(List.of()); when(player.teleport(any(Location.class))).thenReturn(true);
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class)); bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            var run = new RaceRun(UUID.randomUUID(), "Race", "course", 15, 0, List.of(new RaceRun.Racer(id, "Alice")));
            var session = new RaceSession(env.plugin, manager, run, RaceCourse.defaults("course"), new ArenaLease(arena, UUID.randomUUID()));
            session.enable(); assertTrue(session.owns(id)); assertEquals(RaceRun.State.COUNTDOWN, run.state());
            verify(manager).capture(player); kit.verify(() -> RaceKit.give(player));
            verify(first).setMaximumNoDamageTicks(0); verify(first).setInvulnerable(false);
            var countdown = env.scheduled.getFirst();
            for (int i = 0; i < 10; i++) countdown.run(); assertEquals(RaceRun.State.RUNNING, run.state());
            run.hit(id, 2, System.nanoTime());
            Location from = new Location(world, 4, 64, 4), to = new Location(world, 4, 50, 4);
            var fall = new PlayerMoveEvent(player, from, to); session.move(fall);
            assertEquals(67, fall.getTo().getY()); assertEquals(4, fall.getTo().getX());
            var escape = new PlayerTeleportEvent(player, from, new Location(world, 150, 70, 4), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
            session.teleport(escape); assertTrue(escape.isCancelled());
            var wind = new PlayerMoveEvent(player, from, new Location(world, 50, 150, 4)); session.move(wind);
            assertEquals(150, wind.getTo().getY()); // airborne outside template remains inside this cell
            var hunger = mock(FoodLevelChangeEvent.class); when(hunger.getEntity()).thenReturn(player); session.food(hunger); verify(hunger).setCancelled(true);
            session.join(new PlayerJoinEvent(player, Component.empty())); env.scheduled.getLast().run();
            assertEquals(2, run.racer(id).checkpoint()); kit.verify(() -> RaceKit.give(player), times(1));
            session.disable(); verify(manager).restore(player); verify(chunk).removePluginChunkTicket(env.plugin);
            int tasks = env.scheduled.size(); for (var scheduled : List.copyOf(env.scheduled)) scheduled.run(); assertEquals(tasks, env.scheduled.size());
            verify(manager, times(1)).restore(player);
        }
    }
    @Test void missingOrDuplicateCheckpointsFailBeforePlayersAreChanged() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class); when(world.getName()).thenReturn("arena"); bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            Chunk chunk = mock(Chunk.class); when(world.getChunkAt(0, 0)).thenReturn(chunk);
            Entity[] missing = {mob("#1", world, 2), mob("#3", world, 4), mob("Finish", world, 6)};
            when(chunk.getEntities()).thenReturn(missing);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> RaceManager.scan(map(), RaceCourse.defaults("course"))).getMessage().contains("#2"));
            Entity[] duplicates = {mob("#1", world, 2), mob("#1", world, 4), mob("Finish", world, 6)};
            when(chunk.getEntities()).thenReturn(duplicates);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> RaceManager.scan(map(), RaceCourse.defaults("course"))).getMessage().contains("Duplicate"));
        }
    }
    private LivingEntity mob(String name, World world, int x) {
        LivingEntity mob = mock(LivingEntity.class); when(mob.getUniqueId()).thenReturn(UUID.randomUUID()); when(mob.isValid()).thenReturn(true);
        when(mob.customName()).thenReturn(Component.text(name)); when(mob.getLocation()).thenAnswer(i -> new Location(world, x, 64, 4));
        when(mob.getAttribute(any())).thenReturn(mock(AttributeInstance.class)); when(mob.getMaxHealth()).thenReturn(1024d);
        return mob;
    }
    private ArenaMap map() {
        var map = new ArenaMap("course"); map.setWorldName("arena"); map.setRollbackRegion(0, 60, 0, 10, 80, 10);
        map.setTeam1Spawn(1, 64, 1, 0, 0); map.setTeam2Spawn(1, 64, 1, 0, 0); return map;
    }
}
