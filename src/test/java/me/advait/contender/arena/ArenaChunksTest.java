package me.advait.contender.arena;

import me.advait.contender.map.BlockBounds;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArenaChunksTest {
    private static final BlockBounds ONE_CHUNK = new BlockBounds(0, 0, 0, 15, 10, 15);

    @Test void waitsForEntityDataAndKeepsChunksLoadedUntilThePasteFinishes() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk first = mock(Chunk.class), second = mock(Chunk.class);
            when(first.addPluginChunkTicket(server.plugin)).thenReturn(true);
            when(second.addPluginChunkTicket(server.plugin)).thenReturn(true);
            CompletableFuture<Chunk> firstLoad = new CompletableFuture<>(), secondLoad = new CompletableFuture<>();
            when(world.getChunkAtAsync(-1, 0)).thenReturn(firstLoad);
            when(world.getChunkAtAsync(0, 0)).thenReturn(secondLoad);
            CompletableFuture<Void> paste = new CompletableFuture<>();
            AtomicBoolean started = new AtomicBoolean();
            var result = chunks.run(world, new BlockBounds(-1, 0, 0, 15, 10, 15), () -> {
                started.set(true);
                return paste;
            });
            firstLoad.complete(first);
            secondLoad.complete(second);
            assertFalse(started.get(), "Loaded blocks alone must not start the paste");
            chunks.onEntitiesLoad(entityEvent(world, -1, 0));
            assertFalse(started.get(), "Every selected chunk needs entity data");
            chunks.onEntitiesLoad(entityEvent(world, 0, 0));
            assertTrue(started.get());
            assertFalse(result.isDone());
            verify(first, never()).getEntities();
            verify(second, never()).getEntities();
            verify(first, never()).removePluginChunkTicket(server.plugin);
            paste.completeExceptionally(new IllegalStateException("Paste failed"));
            assertThrows(CompletionException.class, result::join);
            verify(first).removePluginChunkTicket(server.plugin);
            verify(second).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void alreadyLoadedEntitiesDoNotWaitForAnotherLoadEvent() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk chunk = loadedChunk(server);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            var result = chunks.run(world, ONE_CHUNK, () -> CompletableFuture.completedFuture("saved"));
            assertEquals("saved", result.join());
            verify(chunk).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void aTimedOutChunkLoadReleasesTicketsRejectsLateLoadsAndAllowsMoreWork() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk first = loadedChunk(server), late = mock(Chunk.class), next = loadedChunk(server);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(first));
            CompletableFuture<Chunk> hanging = new CompletableFuture<>();
            when(world.getChunkAtAsync(1, 0)).thenReturn(hanging);
            AtomicBoolean pasted = new AtomicBoolean();
            var failed = chunks.run(world, new BlockBounds(0, 0, 0, 31, 10, 15), () -> {
                pasted.set(true);
                return CompletableFuture.completedFuture(null);
            });

            server.scheduled.getFirst().run();
            assertThrows(CompletionException.class, failed::join);
            assertFalse(pasted.get());
            verify(first).removePluginChunkTicket(server.plugin);
            hanging.complete(late);
            verify(late, never()).addPluginChunkTicket(server.plugin);
            verify(late, never()).isEntitiesLoaded();

            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(next));
            var repaired = chunks.run(world, ONE_CHUNK, () -> CompletableFuture.completedFuture(null));
            assertDoesNotThrow(repaired::join);
            verify(next).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void entityLoadingSharesTheChunkTimeoutAndLateEventsCannotStartThePaste() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk chunk = mock(Chunk.class);
            when(chunk.addPluginChunkTicket(server.plugin)).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            AtomicBoolean pasted = new AtomicBoolean();
            var result = chunks.run(world, ONE_CHUNK, () -> {
                pasted.set(true);
                return CompletableFuture.completedFuture(null);
            });
            assertFalse(result.isDone());
            server.scheduled.getFirst().run();
            assertThrows(CompletionException.class, result::join);
            chunks.onEntitiesLoad(entityEvent(world, 0, 0));
            assertFalse(pasted.get());
            verify(chunk).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void entityEventsForOtherWorldsOrChunksDoNotReleaseWaitingWork() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk chunk = mock(Chunk.class);
            when(chunk.addPluginChunkTicket(server.plugin)).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            var result = chunks.run(world, ONE_CHUNK, () -> CompletableFuture.completedFuture(null));
            chunks.onEntitiesLoad(entityEvent(mock(World.class), 0, 0));
            chunks.onEntitiesLoad(entityEvent(world, 1, 0));
            assertFalse(result.isDone());
            chunks.onEntitiesLoad(entityEvent(world, 0, 0));
            assertDoesNotThrow(result::join);
        }
    }

    @Test void chunkTimeoutCannotReleaseAnActivePasteOrStartAnotherPaste() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk chunk = loadedChunk(server);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            CompletableFuture<Void> paste = new CompletableFuture<>();
            var result = chunks.run(world, ONE_CHUNK, () -> paste);

            server.scheduled.getFirst().run();
            assertFalse(result.isDone());
            verify(chunk, never()).removePluginChunkTicket(server.plugin);
            paste.complete(null);
            assertDoesNotThrow(result::join);
            verify(chunk).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void overlappingOperationsHoldTheirSharedTicketUntilBothFinish() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk chunk = loadedChunk(server);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            CompletableFuture<Void> first = new CompletableFuture<>(), second = new CompletableFuture<>();
            var firstResult = chunks.run(world, ONE_CHUNK, () -> first);
            var secondResult = chunks.run(world, ONE_CHUNK, () -> second);
            first.complete(null);
            assertDoesNotThrow(firstResult::join);
            verify(chunk, never()).removePluginChunkTicket(server.plugin);
            second.complete(null);
            assertDoesNotThrow(secondResult::join);
            verify(chunk).addPluginChunkTicket(server.plugin);
            verify(chunk).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void anExistingPluginTicketIsNotRemovedByArenaOperations() {
        try (var server = new StateTestServer(); ArenaChunks chunks = new ArenaChunks(server.plugin)) {
            World world = mock(World.class);
            Chunk chunk = mock(Chunk.class);
            when(chunk.isEntitiesLoaded()).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            assertDoesNotThrow(chunks.run(world, ONE_CHUNK, () -> CompletableFuture.completedFuture(null))::join);
            verify(chunk, never()).removePluginChunkTicket(server.plugin);
        }
    }

    @Test void shutdownReleasesLoadedChunksIgnoresLateLoadsAndUnregistersItsListener() {
        try (var server = new StateTestServer()) {
            World world = mock(World.class);
            Chunk first = mock(Chunk.class), second = mock(Chunk.class);
            when(first.addPluginChunkTicket(server.plugin)).thenReturn(true);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(first));
            CompletableFuture<Chunk> lateLoad = new CompletableFuture<>();
            when(world.getChunkAtAsync(1, 0)).thenReturn(lateLoad);
            ArenaChunks chunks = new ArenaChunks(server.plugin);
            AtomicBoolean started = new AtomicBoolean();
            var result = chunks.run(world, new BlockBounds(0, 0, 0, 31, 10, 15), () -> {
                started.set(true);
                return CompletableFuture.completedFuture(null);
            });
            assertEquals(1, server.handlers.getRegisteredListeners().length);
            chunks.close();
            assertEquals(0, server.handlers.getRegisteredListeners().length);
            lateLoad.complete(second);
            chunks.onEntitiesLoad(entityEvent(world, 0, 0));
            assertFalse(started.get());
            assertTrue(result.isCompletedExceptionally());
            verify(first).removePluginChunkTicket(server.plugin);
            verify(second, never()).addPluginChunkTicket(server.plugin);
        }
    }

    private static Chunk loadedChunk(StateTestServer server) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.addPluginChunkTicket(server.plugin)).thenReturn(true);
        when(chunk.isEntitiesLoaded()).thenReturn(true);
        return chunk;
    }

    private static EntitiesLoadEvent entityEvent(World world, int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        return new EntitiesLoadEvent(chunk, java.util.List.of());
    }
}
