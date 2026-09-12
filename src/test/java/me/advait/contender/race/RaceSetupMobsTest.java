package me.advait.contender.race;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceSetupMobsTest {
    @Test void discoversManuallyNamedCheckpointsAndConfiguredFinishInTheSourceSelection() {
        try (var fixture = new Fixture()) {
            fixture.courses.add(new RaceCourse("race", 15, "Done", 3));
            LivingEntity checkpoint = fixture.mob("#12", fixture.source, 4);
            LivingEntity finish = fixture.mob("Done", fixture.source, 8);
            LivingEntity ordinary = fixture.mob("Cow", fixture.source, 6);
            LivingEntity wrongFinish = fixture.mob("Finish", fixture.source, 7);
            LivingEntity outside = fixture.mob("#1", fixture.source, 11);
            fixture.setup.enable();

            assertFrozen(checkpoint);
            assertFrozen(finish);
            for (LivingEntity untouched : List.of(ordinary, wrongFinish, outside)) {
                verify(untouched, never()).setInvulnerable(anyBoolean());
                fixture.assertDamageAllowed(untouched);
            }
        }
    }

    @Test void tagsCopiedIntoArenaEntitiesDoNotTurnTheirHitsInvulnerable() {
        try (var fixture = new Fixture()) {
            fixture.courses.add(RaceCourse.defaults("race"));
            LivingEntity arenaCopy = fixture.mob("#1", fixture.arena, 4);
            fixture.tag(arenaCopy, "race");
            LivingEntity translatedCopy = fixture.mob("#1", fixture.source, 2_000);
            fixture.tag(translatedCopy, "race");
            fixture.setup.enable();

            for (LivingEntity copy : List.of(arenaCopy, translatedCopy)) {
                fixture.assertDamageAllowed(copy);
                verify(copy, never()).setInvulnerable(anyBoolean());
                verify(copy, never()).setVelocity(any());
            }
        }
    }

    @Test void numberedAndFinishRegistrationsAreProtectedBeforeTheCourseIsSaved() {
        try (var fixture = new Fixture()) {
            fixture.setup.enable();
            LivingEntity checkpoint = fixture.mob("#1", fixture.source, 4);
            LivingEntity finish = fixture.mob("Finish", fixture.source, 8);
            fixture.setup.protect(checkpoint, "race");
            fixture.setup.protect(finish, "race");

            for (LivingEntity mob : List.of(checkpoint, finish)) {
                assertFrozen(mob);
                verify(mob.getPersistentDataContainer()).set(
                        RaceSetupMobs.SOURCE_MAP, PersistentDataType.STRING, "race");
                assertCancelled(event(EntityDamageEvent.class, mob), fixture.setup::damage);
            }
            assertTrue(fixture.courses.isEmpty());
        }
    }

    @Test void unfinishedSetupTagsRecoverAcrossRestartAndChunkLoading() {
        try (var fixture = new Fixture()) {
            LivingEntity loaded = fixture.mob("#1", fixture.source, 4);
            fixture.tag(loaded, "race");
            fixture.setup.enable();
            assertFrozen(loaded);

            LivingEntity later = fixture.mob("Finish", fixture.source, 8);
            fixture.tag(later, "race");
            EntitiesLoadEvent loadedChunk = mock(EntitiesLoadEvent.class);
            when(loadedChunk.getEntities()).thenReturn(List.of(later));
            fixture.setup.load(loadedChunk);
            assertFrozen(later);
            assertTrue(fixture.courses.isEmpty(), "Restart recovery must also cover unsaved courses");
        }
    }

    @Test void watchesExistingMobsAndFindsLaterNameChangesWithoutLoadingChunks() {
        try (var fixture = new Fixture()) {
            LivingEntity existing = fixture.mob("#1", fixture.source, 4);
            LivingEntity renamed = fixture.mob(null, fixture.source, 8);
            fixture.setup.enable();
            verify(existing, never()).setInvulnerable(anyBoolean());
            fixture.setup.watch("race");
            assertFrozen(existing);
            verify(renamed, never()).setInvulnerable(anyBoolean());

            when(renamed.customName()).thenReturn(Component.text("#2"));
            fixture.env.scheduled.getFirst().run();
            assertFrozen(renamed);

            fixture.bukkit.verify(() -> Bukkit.getWorld("source"), atLeastOnce());
            for (World world : List.of(fixture.source, fixture.arena)) {
                assertTrue(mockingDetails(world).getInvocations().stream()
                                .noneMatch(call -> call.getMethod().getName().toLowerCase().contains("chunk")),
                        "Checkpoint discovery should scan loaded entities without requesting chunks");
            }
        }
    }

    @Test void sourceMobsCannotBeDamagedMovedBurnedTransformedRiddenOrLeashed() {
        try (var fixture = new Fixture()) {
            fixture.courses.add(RaceCourse.defaults("race"));
            LivingEntity target = fixture.mob("#1", fixture.source, 4);
            fixture.setup.enable();

            assertCancelled(event(EntityDamageEvent.class, target), fixture.setup::damage);
            assertCancelled(event(EntityMoveEvent.class, target), fixture.setup::move);
            assertCancelled(event(EntityKnockbackEvent.class, target), fixture.setup::knockback);
            assertCancelled(event(EntityTeleportEvent.class, target), fixture.setup::teleport);
            assertCancelled(event(EntityCombustEvent.class, target), fixture.setup::combust);
            assertCancelled(event(EntityTargetEvent.class, target), fixture.setup::target);
            assertCancelled(event(EntityTransformEvent.class, target), fixture.setup::transform);
            assertCancelled(event(ExplosionPrimeEvent.class, target), fixture.setup::explode);

            EntityMountEvent checkpointRiding = event(EntityMountEvent.class, target);
            assertCancelled(checkpointRiding, fixture.setup::mount);
            EntityMountEvent checkpointRidden = event(EntityMountEvent.class, mock(Player.class));
            when(checkpointRidden.getMount()).thenReturn(target);
            assertCancelled(checkpointRidden, fixture.setup::mount);
            PlayerLeashEntityEvent leash = mock(PlayerLeashEntityEvent.class);
            when(leash.getEntity()).thenReturn(target);
            assertCancelled(leash, fixture.setup::leash);
            PlayerBucketEntityEvent bucket = mock(PlayerBucketEntityEvent.class);
            when(bucket.getEntity()).thenReturn(target);
            assertCancelled(bucket, fixture.setup::bucket);
        }
    }

    @Test void preventsDeathEvenWhenDamageEventsAreBypassed() {
        try (var fixture = new Fixture()) {
            fixture.courses.add(RaceCourse.defaults("race"));
            LivingEntity checkpoint = fixture.mob("#1", fixture.source, 4);
            LivingEntity unrelated = fixture.mob("Cow", fixture.source, 8);
            fixture.setup.enable();

            EntityDeathEvent death = event(EntityDeathEvent.class, checkpoint);
            assertCancelled(death, fixture.setup::death);
            verify(death).setReviveHealth(20);
            EntityDeathEvent ordinaryDeath = event(EntityDeathEvent.class, unrelated);
            fixture.setup.death(ordinaryDeath);
            verify(ordinaryDeath, never()).setCancelled(anyBoolean());
            verify(ordinaryDeath, never()).setReviveHealth(anyDouble());
        }
    }

    @Test void keepsHostileCheckpointsVisibleAndAliveInAPeacefulSourceWorld() {
        try (var fixture = new Fixture()) {
            fixture.courses.add(RaceCourse.defaults("race"));
            AtomicReference<Difficulty> difficulty = new AtomicReference<>(Difficulty.PEACEFUL);
            when(fixture.source.getDifficulty()).thenAnswer(ignored -> difficulty.get());
            doAnswer(call -> { difficulty.set(call.getArgument(0)); return null; })
                    .when(fixture.source).setDifficulty(any());
            Mob first = fixture.mob(Mob.class, "#1", fixture.source, 4);
            Mob second = fixture.mob(Mob.class, "Finish", fixture.source, 8);
            when(first.shouldDespawnInPeaceful()).thenReturn(true);
            when(second.shouldDespawnInPeaceful()).thenReturn(true);
            fixture.setup.enable();

            assertEquals(Difficulty.NORMAL, difficulty.get());
            verify(fixture.source, times(1)).setDifficulty(Difficulty.NORMAL);
            verify(fixture.env.plugin.getLogger(), times(1)).info(anyString());
            assertFrozen(first);
            assertFrozen(second);
        }
    }

    @Test void recognizesNamedSpawnsAndNeverProtectsPlayers() {
        try (var fixture = new Fixture()) {
            fixture.courses.add(RaceCourse.defaults("race"));
            fixture.setup.enable();
            LivingEntity checkpoint = fixture.mob("#1", fixture.source, 4);
            fixture.setup.spawn(event(CreatureSpawnEvent.class, checkpoint));
            assertFrozen(checkpoint);

            Player player = mock(Player.class);
            fixture.setup.protect(player, "race");
            fixture.assertDamageAllowed(player);
            verify(player, never()).setInvulnerable(anyBoolean());
            verify(player, never()).getPersistentDataContainer();
        }
    }

    private static void assertFrozen(LivingEntity mob) {
        verify(mob).setAI(false);
        verify(mob).setGravity(false);
        verify(mob).setInvulnerable(true);
        verify(mob).setPersistent(true);
        verify(mob).setRemoveWhenFarAway(false);
    }

    private static <T extends EntityEvent> T event(Class<T> type, Entity entity) {
        T event = mock(type);
        when(event.getEntity()).thenReturn(entity);
        return event;
    }

    private static <T extends Cancellable> void assertCancelled(T event, Consumer<T> handler) {
        handler.accept(event);
        verify(event).setCancelled(true);
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer env = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final World source = mock(World.class);
        final World arena = mock(World.class);
        final List<LivingEntity> sourceMobs = new ArrayList<>();
        final List<LivingEntity> arenaMobs = new ArrayList<>();
        final List<RaceCourse> courses = new ArrayList<>();
        final RaceSetupMobs setup = new RaceSetupMobs(env.plugin, () -> courses);

        Fixture() {
            when(source.getName()).thenReturn("source");
            when(arena.getName()).thenReturn("arenas");
            when(source.getLivingEntities()).thenAnswer(ignored -> List.copyOf(sourceMobs));
            when(arena.getLivingEntities()).thenAnswer(ignored -> List.copyOf(arenaMobs));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(source, arena));
            bukkit.when(() -> Bukkit.getWorld("source")).thenReturn(source);
            bukkit.when(() -> Bukkit.getWorld("arenas")).thenReturn(arena);
            MapManager maps = mock(MapManager.class);
            when(env.plugin.getMapManager()).thenReturn(maps);
            when(env.plugin.getLogger()).thenReturn(mock(Logger.class));
            ArenaMap map = new ArenaMap("race");
            map.setWorldName("source");
            map.setRollbackRegion(0, 0, 0, 10, 100, 10);
            when(maps.getMap("race")).thenReturn(map);
        }

        LivingEntity mob(String name, World world, double x) {
            return mob(LivingEntity.class, name, world, x);
        }

        <T extends LivingEntity> T mob(Class<T> type, String name, World world, double x) {
            T mob = mock(type);
            when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
            when(mob.getLocation()).thenReturn(new Location(world, x, 64, 4));
            when(mob.getWorld()).thenReturn(world);
            when(mob.customName()).thenReturn(name == null ? null : Component.text(name));
            when(mob.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(mob.isValid()).thenReturn(true);
            when(mob.getMaxHealth()).thenReturn(20.0);
            (world == source ? sourceMobs : arenaMobs).add(mob);
            return mob;
        }

        void tag(LivingEntity mob, String mapId) {
            when(mob.getPersistentDataContainer().get(RaceSetupMobs.SOURCE_MAP, PersistentDataType.STRING))
                    .thenReturn(mapId);
        }

        void assertDamageAllowed(Entity entity) {
            EntityDamageEvent event = event(EntityDamageEvent.class, entity);
            setup.damage(event);
            verify(event, never()).setCancelled(anyBoolean());
        }

        @Override public void close() {
            setup.disable();
            bukkit.close();
            env.close();
        }
    }
}
