package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.world.ManagedWorlds;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinigameArenasTest {
    @Test void loadsAnUnloadedCourseBeforeLoadingItsCheckpointEntities() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var plugin = mock(Contender.class);
            var worlds = mock(ManagedWorlds.class);
            when(plugin.getManagedWorlds()).thenReturn(worlds);
            World world = mock(World.class);
            when(worlds.loadExisting("course")).thenReturn(world);
            Chunk chunk = mock(Chunk.class);
            when(world.getChunkAtAsync(0, 0)).thenReturn(CompletableFuture.completedFuture(chunk));
            when(chunk.addPluginChunkTicket(plugin)).thenReturn(true);
            ArenaMap map = new ArenaMap("race");
            map.setWorldName("course");
            map.setRollbackRegion(0, 0, 0, 15, 10, 15);
            var tickets = new HashSet<Chunk>();

            MinigameArenas.loadChunks(plugin, map, tickets, () -> true).join();

            verify(worlds).loadExisting("course");
            verify(chunk).getEntities();
            assertTrue(tickets.contains(chunk));
        }
    }

    @Test void reportsWorldLoadingFailureThroughThePreparationFuture() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var plugin = mock(Contender.class);
            var worlds = mock(ManagedWorlds.class);
            when(plugin.getManagedWorlds()).thenReturn(worlds);
            var missing = new IllegalArgumentException("World course was not found.");
            when(worlds.loadExisting("course")).thenThrow(missing);
            ArenaMap map = new ArenaMap("race");
            map.setWorldName("course");

            var result = MinigameArenas.loadChunks(plugin, map, new HashSet<>(), () -> true);

            assertSame(missing, assertThrows(CompletionException.class, result::join).getCause());
        }
    }
}
