package me.advait.contender.arena;

import me.advait.contender.map.BlockBounds;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArenaChunksTest {
    @Test void waitsForEntityDataAndKeepsChunksLoadedUntilThePasteFinishes() {
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        Chunk first = mock(Chunk.class), second = mock(Chunk.class);
        when(first.addPluginChunkTicket(plugin)).thenReturn(true);
        when(second.addPluginChunkTicket(plugin)).thenReturn(true);
        CompletableFuture<Chunk> firstLoad = new CompletableFuture<>(), secondLoad = new CompletableFuture<>();
        when(world.getChunkAtAsync(-1, 0)).thenReturn(firstLoad);
        when(world.getChunkAtAsync(0, 0)).thenReturn(secondLoad);
        CompletableFuture<Void> paste = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        try (ArenaChunks chunks = new ArenaChunks(plugin)) {
            var result = chunks.run(world, new BlockBounds(-1, 0, 0, 15, 10, 15), () -> {
                verify(first).getEntities();
                verify(second).getEntities();
                started.set(true);
                return paste;
            });
            firstLoad.complete(first);
            assertFalse(started.get());
            secondLoad.complete(second);
            assertTrue(started.get());
            assertFalse(result.isDone());
            verify(first, never()).removePluginChunkTicket(plugin);
            paste.completeExceptionally(new IllegalStateException("Paste failed"));
            assertThrows(CompletionException.class, result::join);
            verify(first).removePluginChunkTicket(plugin);
            verify(second).removePluginChunkTicket(plugin);
        }
    }

    @Test void shutdownReleasesLoadedChunksAndIgnoresLateLoads() {
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        Chunk first = mock(Chunk.class), second = mock(Chunk.class);
        when(first.addPluginChunkTicket(plugin)).thenReturn(true);
        when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(first));
        CompletableFuture<Chunk> lateLoad = new CompletableFuture<>();
        when(world.getChunkAtAsync(1, 0)).thenReturn(lateLoad);
        ArenaChunks chunks = new ArenaChunks(plugin);
        AtomicBoolean started = new AtomicBoolean();
        var result = chunks.run(world, new BlockBounds(0, 0, 0, 31, 10, 15), () -> {
            started.set(true);
            return CompletableFuture.completedFuture(null);
        });
        chunks.close();
        lateLoad.complete(second);
        assertFalse(started.get());
        assertTrue(result.isCompletedExceptionally());
        verify(first).removePluginChunkTicket(plugin);
        verify(second, never()).addPluginChunkTicket(plugin);
    }
}
