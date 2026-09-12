package me.advait.contender.manhunt;

import me.advait.contender.Contender;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManhuntWorldCancellationTest {
    @TempDir Path folder;

    @Test void cancelledChunkLoadsCannotReadyOrFailTheNextPreparedWorld() {
        var plugin = mock(Contender.class); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        World first = world(), next = world();
        List<CompletableFuture<Chunk>> firstLoads = new ArrayList<>(), nextLoads = new ArrayList<>();
        when(first.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenAnswer(call -> pending(firstLoads));
        when(next.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenAnswer(call -> pending(nextLoads));
        try (var registries = mockStatic(RegistryAccess.class);
             var bridges = mockStatic(io.papermc.paper.InternalAPIBridge.class);
             var bukkit = mockStatic(Bukkit.class);
             var creators = mockConstruction(WorldCreator.class, (creator, context) -> {
                 when(creator.environment(World.Environment.THE_END)).thenReturn(creator);
                 when(creator.generateStructures(true)).thenReturn(creator);
                 when(creator.createWorld()).thenReturn(context.getCount() == 1 ? first : next);
             })) {
            registries.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
            bridges.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            var gameRules = Registry.GAME_RULE;
            Map<net.kyori.adventure.key.Key, GameRule<?>> rules = new HashMap<>();
            doAnswer(call -> rules.computeIfAbsent(call.getArgument(0), key -> mock(GameRule.class)))
                    .when(gameRules).getOrThrow(any(net.kyori.adventure.key.Key.class));
            var prepared = new ManhuntWorld(plugin);
            var oldPreparation = prepared.prepare(() -> true);
            assertEquals(8, firstLoads.size());
            var loaded = mock(Chunk.class); firstLoads.getFirst().complete(loaded);
            verify(loaded).addPluginChunkTicket(plugin);
            prepared.cancelPreparation();
            assertEquals(ManhuntWorld.State.FAILED, prepared.state());
            verify(loaded).removePluginChunkTicket(plugin);
            prepared.prepare(() -> true);
            assertEquals(ManhuntWorld.State.PREPARING, prepared.state()); assertSame(next, prepared.world());
            for (var load : List.copyOf(firstLoads)) load.complete(mock(Chunk.class));
            assertTrue(oldPreparation.isCompletedExceptionally());
            assertEquals(ManhuntWorld.State.PREPARING, prepared.state()); assertEquals(8, nextLoads.size());
            verify(first, times(8)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
            verify(next, never()).save();
            prepared.close();
        }
    }
    private World world() {
        var world = mock(World.class); when(world.getWorldFolder()).thenReturn(folder.toFile());
        when(world.getPlayers()).thenReturn(List.of()); when(world.getEntities()).thenReturn(List.of());
        return world;
    }
    private CompletableFuture<Chunk> pending(List<CompletableFuture<Chunk>> loads) {
        var future = new CompletableFuture<Chunk>(); loads.add(future); return future;
    }
}
