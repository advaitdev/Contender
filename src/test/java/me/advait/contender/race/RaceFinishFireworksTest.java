package me.advait.contender.race;

import me.advait.contender.testutil.StateTestServer;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceFinishFireworksTest {
    private static final NamespacedKey MARKER = new NamespacedKey("contender", "race_finish_firework");

    @Test void launchesColorfulRocketsAboveFinishWithFusesShorterThanArenaCleanup() {
        try (var fixture = new Fixture()) {
            Location finish = new Location(fixture.world, 10, 70, 20);
            fixture.celebration.launch(finish);

            assertEquals(3, fixture.rockets.size());
            assertEquals(70, finish.getY(), "Launching must not move the supplied finish location");
            var colors = new HashSet<org.bukkit.Color>();
            var fuses = new HashSet<Integer>();
            for (var spawned : fixture.rockets) {
                assertTrue(spawned.location().getY() > finish.getY());
                assertEquals(finish.getWorld(), spawned.location().getWorld());
                verify(spawned.rocket()).setPersistent(false);
                verify(spawned.data()).set(MARKER, PersistentDataType.BYTE, (byte) 1);
                var effect = ArgumentCaptor.forClass(FireworkEffect.class);
                verify(spawned.meta()).addEffect(effect.capture());
                assertTrue(effect.getValue().getColors().size() >= 3);
                colors.addAll(effect.getValue().getColors());
                var fuse = ArgumentCaptor.forClass(Integer.class);
                var order = inOrder(spawned.rocket());
                order.verify(spawned.rocket()).setFireworkMeta(spawned.meta());
                order.verify(spawned.rocket()).setTicksFlown(0);
                order.verify(spawned.rocket()).setTicksToDetonate(fuse.capture());
                assertTrue(fuse.getValue() > 0 && fuse.getValue() < 19,
                        "Native fireworks burst one tick after the fuse and cleanup starts at tick 20");
                fuses.add(fuse.getValue());
            }
            assertTrue(colors.size() >= 6, "The celebration should use several distinct colors");
            assertEquals(3, fuses.size(), "The three bursts should be staggered");
        }
    }

    @Test void preventsOnlyCelebrationDamageForAnyVictim() {
        try (var fixture = new Fixture()) {
            var celebrationRocket = mock(Firework.class);
            var marked = mock(PersistentDataContainer.class);
            when(celebrationRocket.getPersistentDataContainer()).thenReturn(marked);
            when(marked.has(MARKER, PersistentDataType.BYTE)).thenReturn(true);
            var celebrationDamage = damage(celebrationRocket);
            fixture.celebration.damage(celebrationDamage);
            verify(celebrationDamage).setCancelled(true);

            var ordinaryRocket = mock(Firework.class);
            when(ordinaryRocket.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            var ordinaryDamage = damage(ordinaryRocket);
            fixture.celebration.damage(ordinaryDamage);
            verify(ordinaryDamage, never()).setCancelled(anyBoolean());

            var playerDamage = damage(mock(Player.class));
            fixture.celebration.damage(playerDamage);
            verify(playerDamage, never()).setCancelled(anyBoolean());
        }
    }

    @Test void clearingRocketsIsIdempotentAndAllowsTheNextRaceToCelebrate() {
        try (var fixture = new Fixture()) {
            fixture.celebration.launch(new Location(fixture.world, 0, 70, 0));
            List<Spawned> firstRace = List.copyOf(fixture.rockets);
            fixture.celebration.clear();
            fixture.celebration.clear();
            assertTrue(fixture.celebration.isEnabled());
            firstRace.forEach(spawned -> verify(spawned.rocket(), times(1)).remove());

            fixture.celebration.launch(new Location(fixture.world, 100, 70, 0));
            assertEquals(6, fixture.rockets.size());
            fixture.celebration.disable();
            fixture.rockets.forEach(spawned -> verify(spawned.rocket(), times(1)).remove());
            fixture.celebration.launch(new Location(fixture.world, 100, 70, 0));
            assertEquals(6, fixture.rockets.size(), "Disabled celebrations must not spawn rockets");
        }
    }

    @Test void successfulBurstsAreReleasedWithoutSuppressingTheirEffects() {
        try (var fixture = new Fixture()) {
            fixture.celebration.launch(new Location(fixture.world, 0, 70, 0));
            var burstRocket = fixture.rockets.getFirst().rocket();
            var event = new FireworkExplodeEvent(burstRocket);
            fixture.celebration.exploded(event);
            assertFalse(event.isCancelled());
            fixture.celebration.disable();
            verify(burstRocket, never()).remove();
            fixture.rockets.subList(1, 3).forEach(spawned -> verify(spawned.rocket()).remove());
        }
    }

    private static EntityDamageByEntityEvent damage(org.bukkit.entity.Entity source) {
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(source);
        return event;
    }

    private record Spawned(Location location, Firework rocket, FireworkMeta meta, PersistentDataContainer data) { }

    private static final class Fixture implements AutoCloseable {
        private final StateTestServer env = new StateTestServer();
        private final World world = mock(World.class);
        private final List<Spawned> rockets = new ArrayList<>();
        private final RaceFinishFireworks celebration = new RaceFinishFireworks(env.plugin);

        @SuppressWarnings("unchecked")
        private Fixture() {
            when(world.spawn(any(Location.class), eq(Firework.class), any(Consumer.class))).thenAnswer(call -> {
                var rocket = mock(Firework.class);
                var meta = mock(FireworkMeta.class);
                var data = mock(PersistentDataContainer.class);
                when(rocket.getFireworkMeta()).thenReturn(meta);
                when(rocket.getPersistentDataContainer()).thenReturn(data);
                when(rocket.isValid()).thenReturn(true);
                ((Consumer<Firework>) call.getArgument(2)).accept(rocket);
                rockets.add(new Spawned(call.getArgument(0), rocket, meta, data));
                return rocket;
            });
            celebration.enable();
        }

        @Override public void close() { celebration.disable(); env.close(); }
    }
}
