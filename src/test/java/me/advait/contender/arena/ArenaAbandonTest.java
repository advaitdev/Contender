package me.advait.contender.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.map.MapManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaAbandonTest {
    @Test void abandonmentReleasesImmediatelyAndPreservesOtherReadyCopies() throws Exception {
        Fixture f = new Fixture();
        ArenaInstance available = f.instance(1);
        f.pools.put("course", List.of(f.instance, available));
        ArenaLease lease = f.instance.acquire();

        f.manager.abandon(lease);

        assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
        assertFalse(f.instance.isReserved());
        assertFalse(f.manager.isBusy("course"));
        assertEquals(1, f.manager.available("course"));
        assertSame(available, f.manager.acquire("course").instance());
        assertThrows(CompletionException.class, () -> f.manager.reset(lease).join());
    }

    @Test void staleAbandonAndReleaseCannotAffectANewReservation() throws Exception {
        Fixture f = new Fixture();
        ArenaLease old = f.instance.acquire();
        f.manager.release(old);
        ArenaLease current = f.instance.acquire();

        f.manager.abandon(old);
        f.manager.discard(old);
        f.manager.release(old);

        assertTrue(f.instance.owns(current));
        assertEquals(ArenaInstance.Status.IN_USE, f.instance.status());
    }

    @Test void abandonedQueuedResetNeverStartsChunkLoadingAndLeavesQueueUsable() throws Exception {
        try (var loading = mockConstruction(ArenaChunks.class, (chunks, context) ->
                when(chunks.run(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null)))) {
            Fixture f = new Fixture();
            CompletableFuture<Void> priorWork = new CompletableFuture<>();
            field(f.manager, "work", priorWork);
            ArenaLease lease = f.instance.acquire();
            var cancelled = f.manager.reset(lease);
            f.manager.abandon(lease);
            ArenaInstance next = f.instance(1);
            f.pools.put("course", List.of(f.instance, next));
            var resetNext = f.manager.reset(next.acquire());

            priorWork.complete(null);

            assertThrows(CompletionException.class, cancelled::join);
            assertDoesNotThrow(resetNext::join);
            verify(loading.constructed().getFirst(), times(1)).run(eq(f.world), eq(next.map().getBounds()), any());
            verifyNoMoreInteractions(loading.constructed().getFirst());
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
            assertEquals(ArenaInstance.Status.IN_USE, next.status());
        }
    }

    @Test void abandonmentWhileChunksLoadStopsEntityRemovalAndPaste() throws Exception {
        Fixture f = new Fixture();
        Chunk chunk = mock(Chunk.class);
        when(chunk.addPluginChunkTicket(f.plugin)).thenReturn(true);
        CompletableFuture<Chunk> load = new CompletableFuture<>();
        when(f.world.getChunkAtAsync(0, 0)).thenReturn(load);
        ArenaLease lease = f.instance.acquire();
        var reset = f.manager.reset(lease);

        f.manager.abandon(lease);
        load.complete(chunk);

        assertThrows(CompletionException.class, reset::join);
        verify(f.world, never()).getEntities();
        verify(chunk).removePluginChunkTicket(f.plugin);
        assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
        assertFalse(f.instance.isReserved());
    }

    @Test void latePasteCompletionCannotRestoreADiscardedCopy() throws Exception {
        CompletableFuture<Void> paste = new CompletableFuture<>();
        try (var loading = mockConstruction(ArenaChunks.class, (chunks, context) ->
                doReturn(paste).when(chunks).run(any(), any(), any()))) {
            Fixture f = new Fixture();
            ArenaLease lease = f.instance.acquire();
            var reset = f.manager.reset(lease);
            f.manager.discard(lease);
            f.manager.release(lease);

            paste.complete(null);

            assertThrows(CompletionException.class, reset::join);
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
            assertNull(f.manager.acquire("course"));
        }
    }

    @Test void lateSuccessfulPasteCannotReturnAnAbandonedCopyToThePool() throws Exception {
        CompletableFuture<Void> paste = new CompletableFuture<>();
        try (var loading = mockConstruction(ArenaChunks.class, (chunks, context) ->
                doReturn(paste).when(chunks).run(any(), any(), any()))) {
            Fixture f = new Fixture();
            ArenaLease lease = f.instance.acquire();
            var reset = f.manager.reset(lease);
            f.manager.abandon(lease);

            paste.complete(null);

            assertThrows(CompletionException.class, reset::join);
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
            assertFalse(f.instance.isReserved());
            assertFalse(f.manager.hasPendingWork());
            assertNull(f.manager.acquire("course"));
        }
    }

    @Test void abandonedPasteKeepsReloadBlockedAndCannotAffectReplacementCopy() throws Exception {
        CompletableFuture<Void> paste = new CompletableFuture<>();
        try (var loading = mockConstruction(ArenaChunks.class, (chunks, context) ->
                doReturn(paste).when(chunks).run(any(), any(), any()))) {
            Fixture f = new Fixture();
            ArenaLease old = f.instance.acquire();
            var reset = f.manager.reset(old);
            f.manager.abandon(old);
            ArenaInstance replacement = f.instance(0);
            f.pools.put("course", List.of(replacement));
            ArenaLease current = f.manager.acquire("course");
            assertTrue(f.manager.hasPendingWork());
            assertThrows(IllegalStateException.class, f.manager::reloadTemplates);

            paste.completeExceptionally(new IllegalStateException("Old paste failed"));
            f.manager.release(old);
            f.manager.abandon(old);

            assertThrows(CompletionException.class, reset::join);
            assertTrue(replacement.owns(current));
            assertEquals(ArenaInstance.Status.IN_USE, replacement.status());
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
        }
    }

    @Test void staleResetCompletionCannotOverwriteANewerReset() {
        ArenaMap map = new ArenaMap("course");
        ArenaInstance instance = new ArenaInstance(0, map, new BlockBounds(0, 0, 0, 15, 15, 15));
        long first = instance.beginReset();
        long second = instance.beginReset();
        assertFalse(instance.restored(first));
        instance.failed(first);
        assertTrue(instance.isReset(second));
        assertTrue(instance.restored(second));
        assertEquals(ArenaInstance.Status.READY, instance.status());
        instance.failed();
        assertThrows(IllegalStateException.class, instance::beginReset);
    }

    @Test void abandonedCopyCannotBeRebuiltWithAPlayerStillInsideItsCell() throws Exception {
        Fixture f = new Fixture();
        ArenaLease lease = f.instance.acquire();
        f.manager.abandon(lease);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(f.world, 3, 200, 3));
        when(f.world.getPlayers()).thenReturn(List.of(player));

        assertThrows(IllegalStateException.class, () -> f.manager.configure(f.instance.map(), "Course", 1));
        assertThrows(IllegalStateException.class, () -> f.manager.saveBlocks(f.instance.map()));
        assertTrue(f.manager.prepare(f.instance.map()).isCompletedExceptionally());
        assertFalse(f.manager.isBusy("course"));

        when(player.getLocation()).thenReturn(new Location(f.world, 1000, 200, 1000));
        assertDoesNotThrow(() -> f.manager.configure(f.instance.map(), "Course", 1));
    }

    @Test void resetChecksOccupancyAgainAfterChunkLoading() throws Exception {
        Fixture f = new Fixture();
        CompletableFuture<Chunk> load = new CompletableFuture<>();
        Chunk chunk = mock(Chunk.class);
        when(f.world.getChunkAtAsync(0, 0)).thenReturn(load);
        var reset = f.manager.reset(f.instance.acquire());
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(f.world, 3, 200, 3));
        when(f.world.getPlayers()).thenReturn(List.of(player));

        load.complete(chunk);

        assertThrows(CompletionException.class, reset::join);
        verify(f.world, never()).getEntities();
        assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
    }

    private static final class Fixture {
        final Contender plugin = mock(Contender.class, RETURNS_DEEP_STUBS);
        final World world = mock(World.class);
        final ArenaManager manager = new ArenaManager(plugin, mock(MapManager.class));
        final Map<String, List<ArenaInstance>> pools;
        final ArenaInstance instance;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            field(manager, "world", world);
            pools = (Map<String, List<ArenaInstance>>) field(manager, "pools");
            Map<String, Clipboard> clipboards = (Map<String, Clipboard>) field(manager, "clipboards");
            clipboards.put("course", mock(Clipboard.class));
            instance = instance(0);
            pools.put("course", List.of(instance));
        }

        ArenaInstance instance(int slot) {
            ArenaMap map = new ArenaMap("course");
            int x = slot * 1024;
            map.setWorldName("arenas");
            map.setRollbackRegion(x, 0, 0, x + 15, 10, 15);
            ArenaInstance copy = new ArenaInstance(slot, map, new BlockBounds(x, -64, 0, x + 15, 320, 15));
            copy.restored(copy.beginReset());
            return copy;
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
