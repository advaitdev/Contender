package me.advait.contender.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaMaintenanceTest {
    @Test void cleanCachedCopiesSkipPastingAndKeepTheirTemplateForLaterResets() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(true);
            assertDoesNotThrow(() -> f.manager.prepare(f.map).join());

            assertEquals(2, f.manager.available("course"));
            assertTrue(f.pastes.isEmpty());
            ArenaLease lease = f.manager.acquire("course");
            var reset = f.manager.reset(lease);
            assertEquals(1, f.pastes.size(), "A reused copy still has a loaded template for reset");
            f.pastes.getLast().complete(null);
            assertDoesNotThrow(reset::join);
            f.manager.release(lease);
            assertEquals(2, f.manager.available("course"));
        }
    }

    @Test void missingCopyChunksRebuildWithoutDiscardingOtherCachedCopies() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(false);
            var preparation = f.manager.prepare(f.map);
            assertEquals(1, f.manager.available("course"));
            assertEquals(1, f.pastes.size());
            assertFalse(preparation.isDone());
            f.pastes.getLast().complete(null);
            assertDoesNotThrow(preparation::join);
            assertEquals(2, f.manager.available("course"));
        }
    }

    @Test void cachedCopiesCannotBeLeasedUntilTheirTemplateLoads() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(true);
            f.clipboards.clear();
            field(f.manager, "templateWork", new CompletableFuture<Void>());

            var preparation = f.manager.prepare(f.map);

            assertFalse(preparation.isDone());
            assertEquals(0, f.manager.available("course"));
            assertNull(f.manager.acquire("course"));
            assertTrue(f.pastes.isEmpty());
        }
    }

    @Test void dynamicOrUnclassifiedTemplatesRebuildInsteadOfUsingSavedCopies() throws Exception {
        for (Boolean eligibility : new Boolean[]{false, null}) {
            try (Fixture f = new Fixture()) {
                f.installCache(true);
                if (eligibility == null) f.eligibility.clear();
                else f.eligibility.put(f.map.getSchematic(), eligibility);

                var preparation = f.manager.prepare(f.map);

                assertEquals(0, f.manager.available("course"));
                assertEquals(1, f.pastes.size());
                f.pastes.getFirst().complete(null);
                f.pastes.getLast().complete(null);
                assertDoesNotThrow(preparation::join);
                assertEquals(2, f.manager.available("course"));
            }
        }
    }

    @Test void dynamicTemplatesNeverPublishReusableEntriesEvenWhenReady() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(true);
            f.manager.prepare(f.map).join();
            f.eligibility.put(f.map.getSchematic(), false);
            when(f.server.server.isStopping()).thenReturn(true);

            f.manager.shutdown();

            var yaml = YamlConfiguration.loadConfiguration(f.directory.resolve("arena-ready.yml").toFile());
            assertTrue(yaml.getMapList("copies").isEmpty());
            verify(f.world, never()).save(anyBoolean());
        }
    }

    @Test void orderlyShutdownFlushesBeforePublishingOnlyCleanCopies() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(true);
            f.manager.prepare(f.map).join();
            ArenaLease dirty = f.manager.acquire("course");
            f.manager.release(dirty);
            when(f.server.server.isStopping()).thenReturn(true);
            doAnswer(call -> {
                assertFalse(Files.exists(f.directory.resolve("arena-ready.yml")), "Cache publication follows the completed flush");
                return null;
            }).when(f.world).save(true);

            f.manager.shutdown();

            verify(f.world).save(true);
            var yaml = YamlConfiguration.loadConfiguration(f.directory.resolve("arena-ready.yml").toFile());
            assertEquals(1, yaml.getMapList("copies").size());
            assertEquals(1, yaml.getMapList("copies").getFirst().get("slot"));
        }
    }

    @Test void pluginDisableWhileServerRunsDoesNotPublishAReusableCache() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(true);
            f.manager.prepare(f.map).join();

            f.manager.shutdown();

            verify(f.world, never()).save(anyBoolean());
            assertFalse(Files.exists(f.directory.resolve("arena-ready.yml")));
        }
    }

    @Test void failedWorldFlushLeavesNoCacheForNextStartup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.installCache(true);
            f.manager.prepare(f.map).join();
            when(f.server.server.isStopping()).thenReturn(true);
            doThrow(new IllegalStateException("Disk unavailable")).when(f.world).save(true);

            assertDoesNotThrow(f.manager::shutdown);

            assertFalse(Files.exists(f.directory.resolve("arena-ready.yml")));
        }
    }

    @Test void startupPreparesSavedMapsAndRegistersMaintenanceUntilShutdown() throws Exception {
        try (var registries = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
             var bridges = mockStatic(io.papermc.paper.InternalAPIBridge.class);
             Fixture f = new Fixture(); var creators = mockConstruction(WorldCreator.class, withSettings().defaultAnswer(RETURNS_SELF),
                (creator, context) -> when(creator.createWorld()).thenReturn(f.world))) {
            registries.when(io.papermc.paper.registry.RegistryAccess::registryAccess)
                    .thenReturn(mock(io.papermc.paper.registry.RegistryAccess.class, RETURNS_MOCKS));
            bridges.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            var rules = new java.util.HashMap<net.kyori.adventure.key.Key, org.bukkit.GameRule<?>>();
            var gameRulesRegistry = org.bukkit.Registry.GAME_RULE;
            doAnswer(call -> rules.computeIfAbsent(call.getArgument(0), key -> mock(org.bukkit.GameRule.class)))
                    .when(gameRulesRegistry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            LobbyManager lobby = mock(LobbyManager.class);
            when(lobby.getLobbyWorldName()).thenReturn("lobby");
            when(f.server.plugin.getLobbyManager()).thenReturn(lobby);

            f.manager.initialize();

            assertEquals(2, f.manager.instances("course").size());
            assertEquals(1, f.pastes.size());
            assertFalse(f.manager.isEditing("course"));
            f.pastes.getFirst().complete(null);
            assertEquals(1, f.manager.available("course"));
            f.pastes.getLast().complete(null);
            assertEquals(2, f.manager.available("course"));
            var maintenance = f.server.scheduled.stream().filter(StateTestServer.Scheduled::repeating).findFirst().orElseThrow();
            f.manager.shutdown();
            verify(maintenance.task()).cancel();
            maintenance.run();
            assertEquals(2, f.pastes.size());
        }
    }

    @Test void repeatedPreparationJoinsExistingWorkAndReadyCopiesCanBeUsedImmediately() throws Exception {
        try (Fixture f = new Fixture()) {
            var preparation = f.manager.prepare(f.map);
            assertSame(preparation, f.manager.prepare(f.map));
            assertEquals(1, f.pastes.size());

            f.pastes.getFirst().complete(null);

            assertFalse(preparation.isDone());
            assertEquals(1, f.manager.available("course"));
            assertNotNull(f.manager.acquire("course"));
            assertSame(preparation, f.manager.prepare(f.map));
            f.pastes.getLast().complete(null);
            assertDoesNotThrow(preparation::join);
            assertEquals(1, f.manager.available("course"));
            assertDoesNotThrow(() -> f.manager.prepare(f.map).join());
            assertEquals(2, f.pastes.size());
        }
    }

    @Test void oneFailedCopyDoesNotDiscardSuccessfulCopiesAndMaintenanceRepairsIt() throws Exception {
        try (Fixture f = new Fixture()) {
            var preparation = f.manager.prepare(f.map);
            f.pastes.getFirst().complete(null);
            f.pastes.getLast().completeExceptionally(new IllegalStateException("Temporary chunk error"));
            assertThrows(CompletionException.class, preparation::join);
            assertEquals(1, f.manager.available("course"));
            ArenaLease working = f.manager.acquire("course");
            assertNotNull(working);

            f.manager.maintain();

            assertEquals(3, f.pastes.size());
            f.pastes.getLast().complete(null);
            assertTrue(working.instance().owns(working));
            assertEquals(ArenaInstance.Status.IN_USE, working.instance().status());
            assertEquals(1, f.manager.available("course"));
        }
    }

    @Test void failedFirstCopyStopsTheAttemptAndReportsItsErrorImmediately() throws Exception {
        try (Fixture f = new Fixture()) {
            f.map.setCopies(20);
            var preparation = f.manager.prepare(f.map);
            f.pastes.getFirst().completeExceptionally(new IllegalStateException("Chunk loading timed out"));

            assertThrows(CompletionException.class, preparation::join);
            assertEquals(1, f.pastes.size(), "Do not queue nineteen more failing copies before reporting the first failure");
            assertFalse(f.manager.isBusy("course"));
            assertTrue(f.manager.readiness("course").contains("Chunk loading timed out"));
            assertFalse(f.manager.readiness("course").contains("Preparing the remaining"));

            f.manager.maintain();
            assertEquals(2, f.pastes.size());
            assertTrue(f.manager.readiness("course").contains("Loading chunks and entities for copy 1 of 20"));
            assertTrue(f.manager.readiness("course").contains("Last attempt: Chunk loading timed out"));
        }
    }

    @Test void savingANewSourceMapDoesNotWaitForAnActiveArenaPaste() throws Exception {
        try (Fixture f = new Fixture(); var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            var preparation = f.manager.prepare(f.map);
            ArenaMap sourceMap = new ArenaMap("new_map");
            sourceMap.setWorldName("source");
            sourceMap.setRollbackRegion(0, 0, 0, 15, 10, 15);
            World source = mock(World.class);
            bukkit.when(() -> org.bukkit.Bukkit.getWorld("source")).thenReturn(source);
            var capture = ArenaManager.class.getDeclaredMethod("capture", ArenaMap.class);
            capture.setAccessible(true);

            var saving = (CompletableFuture<?>) capture.invoke(f.manager, sourceMap);

            assertEquals(2, f.pastes.size(), "Source capture starts while the first arena copy is still pending");
            assertTrue(f.manager.operationStatus("new_map").contains("Loading the source map"));
            assertTrue(f.manager.readiness("course").contains("copy 1 of 2"));
            f.pastes.getLast().complete(null);
            assertDoesNotThrow(saving::join);
            assertNotNull(sourceMap.getSchematic());
            assertFalse(preparation.isDone());
            assertFalse(f.pastes.getFirst().isDone());
        }
    }

    @Test void failedCopiesWaitForOccupantsAndReservationsBeforeAutomaticRepair() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prepareAll();
            ArenaLease occupied = f.manager.acquire("course");
            ArenaLease reserved = f.manager.acquire("course");
            f.manager.abandon(occupied);
            f.manager.discard(reserved);
            Player player = mock(Player.class);
            var cell = occupied.instance().cell();
            when(player.getLocation()).thenReturn(new Location(f.world, cell.minX() + 2, 200, cell.minZ() + 2));
            when(f.world.getPlayers()).thenReturn(List.of(player));

            f.manager.maintain();
            assertEquals(2, f.pastes.size());
            assertTrue(f.manager.readiness("course").contains("when their players leave"));

            when(f.world.getPlayers()).thenReturn(List.of());
            f.manager.maintain();
            assertEquals(3, f.pastes.size());
            f.pastes.getLast().complete(null);
            assertTrue(reserved.instance().owns(reserved));
            f.manager.release(reserved);
            f.manager.maintain();
            f.pastes.getLast().complete(null);
            assertEquals(2, f.manager.available("course"));
        }
    }

    @Test void anAbandonedPasteMustFinishBeforeItsCopyCanBeRepaired() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prepareAll();
            ArenaLease lease = f.manager.acquire("course");
            var reset = f.manager.reset(lease);
            f.manager.abandon(lease);
            var cell = lease.instance().cell();
            assertTrue(f.manager.isPreparingEntry(new Location(mock(World.class), 0, 70, 0),
                    new Location(f.world, cell.minX() + 2, 70, cell.minZ() + 2)));

            f.manager.maintain();
            assertEquals(3, f.pastes.size());
            assertThrows(IllegalStateException.class, () -> f.manager.configure(f.map, "Changed", 2));
            f.pastes.getLast().complete(null);
            assertThrows(CompletionException.class, reset::join);
            f.manager.maintain();
            assertEquals(4, f.pastes.size());
            f.pastes.getLast().complete(null);
            assertEquals(2, f.manager.available("course"));
        }
    }

    @Test void repeatedFailuresUseBackoffAndManualChecksJoinActiveRetries() throws Exception {
        try (Fixture f = new Fixture()) {
            f.map.setCopies(1);
            f.manager.maintain();
            f.pastes.getLast().completeExceptionally(new IllegalStateException("Chunk unavailable"));
            f.manager.maintain();
            assertEquals(2, f.pastes.size());
            var retry = f.manager.prepare(f.map);
            assertFalse(retry.isDone());
            assertEquals(2, f.pastes.size());
            f.pastes.getLast().completeExceptionally(new IllegalStateException("Chunk unavailable"));
            f.manager.maintain();
            assertEquals(2, f.pastes.size());
            f.manager.maintain();
            assertEquals(3, f.pastes.size());
            f.pastes.getLast().complete(null);
            assertEquals(1, f.manager.available("course"));
        }
    }

    @Test void spawnAndConfigurationChangesPrepareNextTickWithoutBlockingOtherChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            f.map = new ArenaMap("course");
            f.map.setWorldName("source");
            f.map.setRollbackRegion(0, 0, 0, 15, 10, 15);
            f.map.setCopies(2);
            when(f.maps.getMaps()).thenReturn(List.of(f.map));
            when(f.maps.getMap("course")).thenReturn(f.map);
            World source = mock(World.class);
            when(source.getName()).thenReturn("source");

            f.manager.setSpawn(f.map, "1", new Location(source, 2, 2, 2));
            f.manager.setSpawn(f.map, "2", new Location(source, 12, 2, 12));
            f.manager.setSpawn(f.map, "spectator", new Location(source, 8, 8, 8));
            f.manager.configure(f.map, "Practice", 3);
            assertEquals(0, f.pastes.size());
            assertEquals(1, f.server.scheduled.size());

            f.server.scheduled.getFirst().run();
            assertEquals(3, f.manager.instances("course").size());
            f.pastes.getFirst().complete(null);
            f.pastes.getLast().complete(null);
            f.pastes.getLast().complete(null);
            assertEquals(3, f.manager.available("course"));
        }
    }

    @Test void inlineQueueCallbacksCannotOvertakeTheirParentOperation() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> order = new ArrayList<>();
            var enqueue = ArenaManager.class.getDeclaredMethod("enqueue", Supplier.class);
            enqueue.setAccessible(true);
            Supplier<CompletableFuture<Void>> parent = () -> {
                order.add("parent started");
                try {
                    enqueue.invoke(f.manager, (Supplier<CompletableFuture<Void>>) () -> {
                        order.add("child");
                        return CompletableFuture.completedFuture(null);
                    });
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                order.add("parent finished");
                return CompletableFuture.completedFuture(null);
            };

            ((CompletableFuture<?>) enqueue.invoke(f.manager, parent)).join();

            assertEquals(List.of("parent started", "parent finished", "child"), order);
        }
    }

    @Test void preparationBlocksArrivalAcrossTheWholeSlotWhileExistingOccupantsCanLeave() throws Exception {
        try (Fixture f = new Fixture()) {
            f.manager.prepare(f.map);
            Location lobby = new Location(mock(World.class), 0, 64, 0);
            Location margin = new Location(f.world, 1, 200, 1);
            Location second = new Location(f.world, 1025, 200, 1);
            Location outside = new Location(f.world, -1, 200, 1);
            assertTrue(f.manager.isPreparingEntry(lobby, margin));
            assertTrue(f.manager.isPreparingEntry(outside, margin));
            assertTrue(f.manager.isPreparingEntry(margin, second));
            assertFalse(f.manager.isPreparingEntry(margin, margin.clone().add(1, 0, 0)));
            assertFalse(f.manager.isPreparingEntry(margin, lobby));
            assertFalse(f.manager.isPreparingEntry(margin, outside));
            f.pastes.getFirst().complete(null);
            assertFalse(f.manager.isPreparingEntry(lobby, margin));
            assertTrue(f.manager.isPreparingEntry(lobby, second));
            f.pastes.getLast().complete(null);
            assertFalse(f.manager.isPreparingEntry(lobby, second));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final MapManager maps = mock(MapManager.class);
        final World world = mock(World.class);
        ArenaMap map = new ArenaMap("course");
        final List<CompletableFuture<Void>> pastes = new ArrayList<>();
        final org.mockito.MockedConstruction<ArenaChunks> chunks;
        final ArenaManager manager;
        final Path directory = Files.createTempDirectory("contender-arena-maintenance-");
        final Path worldFolder = Files.createDirectories(directory.resolve("world"));
        final Map<String, Clipboard> clipboards;
        final Map<String, Boolean> eligibility;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            chunks = mockConstruction(ArenaChunks.class, (loading, context) -> doAnswer(call -> {
                CompletableFuture<Void> paste = new CompletableFuture<>();
                pastes.add(paste);
                return paste;
            }).when(loading).run(any(), any(), any()));
            manager = new ArenaManager(server.plugin, maps);
            when(server.plugin.getDataFolder()).thenReturn(directory.toFile());
            when(server.plugin.getName()).thenReturn("Contender");
            when(server.plugin.namespace()).thenReturn("contender");
            when(server.plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(server.plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(world.getName()).thenReturn("contender_arenas");
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            map.setWorldName("source");
            map.setRollbackRegion(0, 0, 0, 15, 10, 15);
            map.setTeam1Spawn(2, 2, 2, 0, 0);
            map.setTeam2Spawn(12, 2, 12, 0, 0);
            map.setCopies(2);
            map.setSchematic("course-original.schem");
            Files.createDirectories(directory.resolve("maps"));
            Files.writeString(directory.resolve("maps/course-original.schem"), "saved template");
            when(maps.getMaps()).thenReturn(List.of(map));
            when(maps.getMap("course")).thenReturn(map);
            field(manager, "world", world);
            field(manager, "spacing", 1024);
            var stored = ArenaManager.class.getDeclaredField("clipboards");
            stored.setAccessible(true);
            clipboards = (Map<String, Clipboard>) stored.get(manager);
            clipboards.put("course", mock(Clipboard.class));
            var eligibilityField = ArenaManager.class.getDeclaredField("templateCacheEligibility");
            eligibilityField.setAccessible(true);
            eligibility = (Map<String, Boolean>) eligibilityField.get(manager);
        }

        void installCache(boolean secondHasChunks) throws Exception {
            ArenaReadyCache cache = new ArenaReadyCache(directory);
            ArenaInstance first = ArenaLayout.place(map, world.getName(), 0, 1024, -64, 320);
            ArenaInstance second = ArenaLayout.place(map, world.getName(), 1, 1024, -64, 320);
            ArenaReadyCacheTest.writeChunks(worldFolder, first.map().getBounds());
            if (secondHasChunks) ArenaReadyCacheTest.writeChunks(worldFolder, second.map().getBounds());
            cache.write(List.of(cache.entry(map, first, world, 1024), cache.entry(map, second, world, 1024)));
            cache.consume();
            field(manager, "readyCache", cache);
            eligibility.put(map.getSchematic(), true);
        }

        void prepareAll() {
            var preparation = manager.prepare(map);
            pastes.getFirst().complete(null);
            pastes.getLast().complete(null);
            assertDoesNotThrow(preparation::join);
        }

        @Override public void close() {
            manager.shutdown();
            chunks.close();
            server.close();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
