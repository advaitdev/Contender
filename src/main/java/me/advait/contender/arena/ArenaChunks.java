package me.advait.contender.arena;

import me.advait.contender.map.BlockBounds;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Keeps blocks and entity data loaded for serialized arena operations. Called on the server thread. */
final class ArenaChunks implements AutoCloseable {
    private final Plugin plugin;
    private final Set<Chunk> tickets = new HashSet<>();
    private final Set<CompletableFuture<Void>> loading = new HashSet<>();
    private boolean closed;

    ArenaChunks(Plugin plugin) { this.plugin = plugin; }

    <T> CompletableFuture<T> run(World world, BlockBounds bounds, Supplier<CompletableFuture<T>> action) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
        List<Chunk> held = new ArrayList<>();
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        try {
            for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
                for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
                    // Paper completes chunk futures on the server thread.
                    requests.add(world.getChunkAtAsync(x, z).thenAccept(chunk -> {
                        if (closed) throw new IllegalStateException("Arena preparation stopped.");
                        if (chunk.addPluginChunkTicket(plugin)) {
                            held.add(chunk);
                            tickets.add(chunk);
                        }
                        // Blocks being loaded does not imply entity data has been loaded.
                        chunk.getEntities();
                    }));
                }
            }
        } catch (Exception failure) {
            requests.add(CompletableFuture.failedFuture(failure));
        }
        CompletableFuture<Void> ready = CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new));
        loading.add(ready);
        return ready.thenCompose(ignored -> {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
            return action.get();
        }).whenComplete((ignored, failure) -> {
            loading.remove(ready);
            for (Chunk chunk : held) {
                if (tickets.remove(chunk)) chunk.removePluginChunkTicket(plugin);
            }
        });
    }

    @Override public void close() {
        closed = true;
        for (var request : new ArrayList<>(loading)) request.cancel(false);
        loading.clear();
        for (Chunk chunk : new ArrayList<>(tickets)) chunk.removePluginChunkTicket(plugin);
        tickets.clear();
    }
}
