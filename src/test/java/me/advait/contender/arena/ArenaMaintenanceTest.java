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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaMaintenanceTest {
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
            f.pastes.getFirst().completeExceptionally(new IllegalStateException("Temporary chunk error"));
            f.pastes.getLast().complete(null);
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

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            chunks = mockConstruction(ArenaChunks.class, (loading, context) -> doAnswer(call -> {
                CompletableFuture<Void> paste = new CompletableFuture<>();
                pastes.add(paste);
                return paste;
            }).when(loading).run(any(), any(), any()));
            manager = new ArenaManager(server.plugin, maps);
            when(server.plugin.getName()).thenReturn("Contender");
            when(server.plugin.namespace()).thenReturn("contender");
            when(server.plugin.getConfig()).thenReturn(new YamlConfiguration());
            when(server.plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(world.getName()).thenReturn("contender_arenas");
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            map.setWorldName("source");
            map.setRollbackRegion(0, 0, 0, 15, 10, 15);
            map.setTeam1Spawn(2, 2, 2, 0, 0);
            map.setTeam2Spawn(12, 2, 12, 0, 0);
            map.setCopies(2);
            when(maps.getMaps()).thenReturn(List.of(map));
            when(maps.getMap("course")).thenReturn(map);
            field(manager, "world", world);
            field(manager, "spacing", 1024);
            var clipboards = ArenaManager.class.getDeclaredField("clipboards");
            clipboards.setAccessible(true);
            ((Map<String, Clipboard>) clipboards.get(manager)).put("course", mock(Clipboard.class));
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
        }
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
