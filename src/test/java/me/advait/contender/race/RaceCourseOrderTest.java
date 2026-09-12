package me.advait.contender.race;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.testutil.StateTestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.world.WorldLoadEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceCourseOrderTest {
    @Test void removingAMiddleCheckpointClosesTheGapAndKeepsTheFinishName() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), middle = f.mob("#2", 2), last = f.mob("#3", 3), finish = f.mob("Finish", 4);
            assertTrue(f.setup.remove(middle, "race"));
            assertTrue(f.setup.repairing("race"));
            CompletableFuture<Void> completed = f.setup.settled("race");
            assertFalse(completed.isDone());
            assertEquals("#3", name(last));
            f.nextTick();
            completed.join();
            assertEquals(List.of("#1", "#2", "Finish"), List.of(name(first), name(last), name(finish)));
            assertFalse(f.setup.repairing("race"));
            verify(middle).remove();
            verify(f.chunk(0)).removePluginChunkTicket(f.env.plugin);
        }
    }

    @Test void finishRemovalLeavesTheCheckpointOrderUntouched() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), second = f.mob("#2", 2), finish = f.mob("Finish", 3);
            assertTrue(f.setup.remove(finish, "race"));
            f.nextTick();
            assertEquals(List.of("#1", "#2"), List.of(name(first), name(second)));
            verify(first, never()).customName(any());
            verify(second, never()).customName(any());
            assertFalse(f.mobs.contains(finish));
        }
    }

    @Test void bulkRemovalsShareOneRepairAndPreserveNumericOrder() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), second = f.mob("#2", 2), third = f.mob("#3", 3), fourth = f.mob("#4", 4);
            f.setup.remove(first, "race");
            CompletableFuture<Void> repair = f.setup.settled("race");
            f.setup.remove(third, "race");
            assertSame(repair, f.setup.settled("race"));
            f.nextTick();
            assertEquals("#1", name(second));
            assertEquals("#2", name(fourth));
            verify(f.world, times(1)).getChunkAtAsync(0, 0);
        }
    }

    @Test void waitsForUnloadedCheckpointChunksBeforeRenumberingAnySurvivors() {
        try (var f = new Fixture()) {
            f.map.setRollbackRegion(0, 0, 0, 47, 100, 10);
            LivingEntity first = f.mob("#1", 1), near = f.mob("#3", 3);
            CompletableFuture<Chunk> distant = new CompletableFuture<>();
            when(f.world.getChunkAtAsync(2, 0)).thenReturn(distant);
            f.setup.remove(first, "race");
            CompletableFuture<Void> repair = f.setup.settled("race");
            f.nextTick();
            assertFalse(repair.isDone());
            assertEquals("#3", name(near), "Do not compact only the loaded portion of a long course");
            LivingEntity far = f.mob("#2", 36);
            distant.complete(f.chunk(2));
            repair.join();
            assertEquals("#1", name(far));
            assertEquals("#2", name(near));
            for (int x = 0; x < 3; x++) verify(f.chunk(x)).removePluginChunkTicket(f.env.plugin);
        }
    }

    @Test void removalsWhileChunksLoadAreIncludedInTheSameRepair() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), second = f.mob("#2", 2), third = f.mob("#3", 3);
            CompletableFuture<Chunk> loading = new CompletableFuture<>();
            when(f.world.getChunkAtAsync(0, 0)).thenReturn(loading);
            f.setup.remove(first, "race");
            CompletableFuture<Void> repair = f.setup.settled("race");
            f.nextTick();
            f.setup.remove(second, "race");
            assertSame(repair, f.setup.settled("race"));
            loading.complete(f.chunk(0));
            repair.join();
            assertEquals("#1", name(third));
            verify(f.world, times(1)).getChunkAtAsync(0, 0);
        }
    }

    @Test void observesActualRemovalBeforeTheEntityLeavesItsChunk() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), removed = f.mob("#2", 2), third = f.mob("#3", 3);
            f.setup.remove(new EntityRemoveEvent(removed, EntityRemoveEvent.Cause.DEATH));
            assertTrue(f.mobs.contains(removed), "Native remove events precede world-list removal");
            f.nextTick();
            assertEquals("#1", name(first));
            assertEquals("#2", name(third));
            verify(removed, never()).customName(any());
            verify(removed, never()).remove();
        }
    }

    @Test void unloadingChunksAndRemovingArenaCopiesNeverReordersTheSource() {
        try (var f = new Fixture()) {
            LivingEntity source = f.mob("#1", 1), outside = f.mob("#2", 100);
            World arenaWorld = mock(World.class);
            when(arenaWorld.getName()).thenReturn("arenas");
            LivingEntity copy = f.mob("#1", 2);
            when(copy.getLocation()).thenReturn(new Location(arenaWorld, 2, 64, 4));
            when(copy.getWorld()).thenReturn(arenaWorld);
            when(copy.getPersistentDataContainer().get(RaceSetupMobs.SOURCE_MAP, PersistentDataType.STRING)).thenReturn("race");
            f.setup.remove(new EntityRemoveEvent(source, EntityRemoveEvent.Cause.UNLOAD));
            f.setup.remove(new EntityRemoveEvent(source, EntityRemoveEvent.Cause.PLAYER_QUIT));
            f.setup.remove(new EntityRemoveEvent(copy, EntityRemoveEvent.Cause.PLUGIN));
            f.setup.remove(new EntityRemoveEvent(outside, EntityRemoveEvent.Cause.PLUGIN));
            assertFalse(f.setup.remove(copy, "race"));
            assertFalse(f.setup.remove(outside, "race"));
            assertFalse(f.setup.remove(outside, null));
            assertFalse(f.setup.repairing("race"));
            verify(f.world, never()).getChunkAtAsync(anyInt(), anyInt());
        }
    }

    @Test void markingAnEarlierCheckpointAsFinishCanCloseItsFormerGap() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), finish = f.mob("#2", 2), last = f.mob("#3", 3);
            finish.customName(Component.text("Finish"));
            CompletableFuture<Void> repair = f.setup.repairOrder("race");
            f.nextTick();
            repair.join();
            assertEquals(List.of("#1", "Finish", "#2"), List.of(name(first), name(finish), name(last)));
        }
    }

    @Test void disablingDuringChunkLoadingDoesNotRenameOrLeakChunkTickets() {
        try (var f = new Fixture()) {
            f.map.setRollbackRegion(0, 0, 0, 31, 100, 10);
            LivingEntity first = f.mob("#1", 1), third = f.mob("#3", 3);
            CompletableFuture<Chunk> loading = new CompletableFuture<>();
            when(f.world.getChunkAtAsync(1, 0)).thenReturn(loading);
            f.setup.remove(first, "race");
            CompletableFuture<Void> repair = f.setup.settled("race");
            f.nextTick();
            f.setup.disable();
            assertTrue(repair.isCompletedExceptionally());
            loading.complete(f.chunk(1));
            assertEquals("#3", name(third));
            verify(f.chunk(0), times(1)).removePluginChunkTicket(f.env.plugin);
            verify(f.chunk(1), never()).addPluginChunkTicket(any());
            assertFalse(f.setup.repairing("race"));
        }
    }

    @Test void failedChunkLoadDoesNotRenumberAPartialCourse() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), third = f.mob("#3", 3);
            when(f.world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Cannot load chunk")));
            f.setup.remove(first, "race");
            CompletableFuture<Void> repair = f.setup.settled("race");
            f.nextTick();
            assertTrue(repair.isCompletedExceptionally());
            assertEquals("#3", name(third));
            verify(f.env.plugin.getLogger()).warning(contains("Cannot load chunk"));
            when(f.world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(f.chunk(0)));
            CompletableFuture<Void> retry = f.setup.settled("race");
            assertFalse(retry.isDone());
            f.nextTick();
            retry.join();
            assertEquals("#1", name(third), "A later save must retry a failed repair before capturing the course");
        }
    }

    @Test void restartingBeforeTheRepairRunsRecoversThePendingCourse() {
        try (var f = new Fixture()) {
            LivingEntity first = f.mob("#1", 1), removed = f.mob("#2", 2), third = f.mob("#3", 3);
            f.setup.remove(removed, "race");
            File pendingFile = new File(f.folder, "race-course-edits.yml");
            assertEquals(List.of("race"), YamlConfiguration.loadConfiguration(pendingFile).getStringList("pending"));
            f.setup.disable();
            f.setup.enable();
            f.nextTick();
            f.nextTick();
            assertEquals(List.of("#1", "#2"), List.of(name(first), name(third)));
            assertTrue(YamlConfiguration.loadConfiguration(pendingFile).getStringList("pending").isEmpty());
            verify(f.world, times(1)).getChunkAtAsync(0, 0);
        }
    }

    @Test void recoveryWaitsForABuilderWorldToLoadAfterRestart() {
        try (var f = new Fixture()) {
            LivingEntity removed = f.mob("#1", 1), remaining = f.mob("#2", 2);
            f.setup.remove(removed, "race");
            f.setup.disable();
            f.bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            f.bukkit.when(() -> Bukkit.getWorld("source")).thenReturn(null);
            f.setup.enable();
            f.nextTick();
            assertEquals("#2", name(remaining));
            verify(f.world, never()).getChunkAtAsync(anyInt(), anyInt());
            f.bukkit.when(() -> Bukkit.getWorld("source")).thenReturn(f.world);
            f.setup.worldLoad(new WorldLoadEvent(f.world));
            f.nextTick();
            assertEquals("#1", name(remaining));
        }
    }

    @Test void explicitKillIsAllowedWithoutRestoringTheDyingMob() {
        try (var f = new Fixture(); var registry = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, RETURNS_MOCKS);
            registry.when(RegistryAccess::registryAccess).thenReturn(access);
            Registry<DamageType> types = mock(Registry.class);
            when(access.getRegistry(RegistryKey.DAMAGE_TYPE)).thenReturn(types);
            LivingEntity checkpoint = f.mob("#1", 1), following = f.mob("#2", 2);
            EntityDamageEvent damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(checkpoint);
            when(damage.getCause()).thenReturn(EntityDamageEvent.DamageCause.KILL);
            f.setup.damage(damage);
            verify(damage, never()).setCancelled(anyBoolean());
            DamageSource source = mock(DamageSource.class);
            DamageType type = mock(DamageType.class);
            when(type.getKey()).thenReturn(NamespacedKey.minecraft("generic_kill"));
            when(source.getDamageType()).thenReturn(type);
            EntityDeathEvent death = mock(EntityDeathEvent.class);
            when(death.getEntity()).thenReturn(checkpoint);
            when(death.getDamageSource()).thenReturn(source);
            f.setup.death(death);
            verify(death, never()).setCancelled(anyBoolean());
            f.setup.killed(death);
            f.env.scheduled.getFirst().run();
            verify(checkpoint, never()).setHealth(anyDouble());
            f.nextTick();
            assertEquals("#1", name(following));
            f.setup.remove(new EntityRemoveEvent(checkpoint, EntityRemoveEvent.Cause.DEATH));
            checkpoint.remove();
            f.nextTick();
            assertEquals("#1", name(following));
            verify(f.world, times(1)).getChunkAtAsync(0, 0);
        }
    }

    private static String name(LivingEntity mob) { return RaceManager.plainName(mob); }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer env = new StateTestServer();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final World world = mock(World.class);
        final ArenaMap map = new ArenaMap("race");
        final List<LivingEntity> mobs = new ArrayList<>();
        final Map<Integer, Chunk> chunks = new HashMap<>();
        final RaceSetupMobs setup = new RaceSetupMobs(env.plugin, () -> List.of(RaceCourse.defaults("race")));
        final File folder = new File("/private/tmp/contender-course-order-" + UUID.randomUUID());
        int nextTask;

        Fixture() {
            when(env.plugin.getDataFolder()).thenReturn(folder);
            when(world.getName()).thenReturn("source");
            when(world.getLivingEntities()).thenAnswer(ignored -> List.copyOf(mobs));
            when(world.getChunkAt(anyInt(), eq(0))).thenAnswer(call -> chunk(call.getArgument(0)));
            when(world.getChunkAtAsync(anyInt(), eq(0))).thenAnswer(call -> CompletableFuture.completedFuture(chunk(call.getArgument(0))));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(() -> Bukkit.getWorld("source")).thenReturn(world);
            MapManager maps = mock(MapManager.class);
            when(env.plugin.getMapManager()).thenReturn(maps);
            when(env.plugin.getLogger()).thenReturn(mock(Logger.class));
            map.setWorldName("source");
            map.setRollbackRegion(0, 0, 0, 10, 100, 10);
            when(maps.getMap("race")).thenReturn(map);
            setup.enable();
        }

        Chunk chunk(int x) {
            return chunks.computeIfAbsent(x, ignored -> {
                Chunk chunk = mock(Chunk.class);
                when(chunk.addPluginChunkTicket(env.plugin)).thenReturn(true);
                when(chunk.getEntities()).thenAnswer(call -> mobs.stream()
                        .filter(mob -> (mob.getLocation().getBlockX() >> 4) == x).toArray(Entity[]::new));
                return chunk;
            });
        }

        LivingEntity mob(String initialName, double x) {
            LivingEntity mob = mock(LivingEntity.class);
            AtomicReference<Component> name = new AtomicReference<>(Component.text(initialName));
            AtomicBoolean valid = new AtomicBoolean(true);
            when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
            when(mob.getLocation()).thenReturn(new Location(world, x, 64, 4));
            when(mob.getWorld()).thenReturn(world);
            when(mob.customName()).thenAnswer(ignored -> name.get());
            doAnswer(call -> { name.set(call.getArgument(0)); return null; }).when(mob).customName(any());
            when(mob.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(mob.isValid()).thenAnswer(ignored -> valid.get());
            when(mob.getMaxHealth()).thenReturn(20.0);
            doAnswer(call -> { valid.set(false); mobs.remove(mob); return null; }).when(mob).remove();
            mobs.add(mob);
            return mob;
        }

        void nextTick() {
            List<StateTestServer.Scheduled> pending = List.copyOf(env.scheduled.subList(nextTask, env.scheduled.size()));
            nextTask = env.scheduled.size();
            pending.stream().filter(task -> !task.repeating()).forEach(StateTestServer.Scheduled::run);
        }

        @Override public void close() {
            setup.disable(); bukkit.close(); env.close();
            if (folder.exists()) try (var paths = Files.walk(folder.toPath())) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        }
    }
}
