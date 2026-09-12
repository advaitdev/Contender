package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import org.bukkit.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Loads entity data as well as blocks, retaining only chunk tickets owned by this operation. */
public final class MinigameArenas {
    private MinigameArenas() { }
    public static CompletableFuture<Void> loadChunks(Contender plugin, ArenaMap map, Set<Chunk> tickets, BooleanSupplier active) {
        World world;
        try {
            world = Bukkit.getWorld(map.getWorldName());
            if (world == null) world = plugin.getManagedWorlds().loadExisting(map.getWorldName());
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        List<CompletableFuture<?>> pending = new ArrayList<>(); var b = map.getBounds();
        for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
            pending.add(world.getChunkAtAsync(x, z).thenAccept(chunk -> {
                if (!active.getAsBoolean()) throw new IllegalStateException("Race preparation stopped.");
                if (chunk.addPluginChunkTicket(plugin)) tickets.add(chunk);
                chunk.getEntities();
            }));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }
}
