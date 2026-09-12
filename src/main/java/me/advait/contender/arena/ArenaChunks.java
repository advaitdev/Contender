package me.advait.contender.arena;

import me.advait.contender.map.BlockBounds;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Holds block and entity data for arena operations. All callbacks run on the server thread. */
final class ArenaChunks implements AutoCloseable, Listener {
    private record ChunkKey(World world, int x, int z) { }
    private record EntityWait(ChunkKey key, CompletableFuture<Void> ready) { }
    private static final class Ticket {
        private final Chunk chunk;
        private final boolean owned;
        private int references = 1;
        private Ticket(Chunk chunk, boolean owned) { this.chunk = chunk; this.owned = owned; }
    }

    private final Plugin plugin;
    private final Map<ChunkKey, Ticket> tickets = new HashMap<>();
    private final Map<ChunkKey, List<CompletableFuture<Void>>> entityLoads = new HashMap<>();
    private final Set<CompletableFuture<Void>> loading = new HashSet<>();
    private boolean closed;

    ArenaChunks(Plugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    <T> CompletableFuture<T> run(World world, BlockBounds bounds, Supplier<CompletableFuture<T>> action) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
        List<ChunkKey> held = new ArrayList<>();
        List<EntityWait> waiting = new ArrayList<>();
        AtomicBoolean accepting = new AtomicBoolean(true);
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        loading.add(ready);
        try {
            // Covers both block loading and entity loading, but never an active FAWE paste.
            BukkitTask timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    ready.completeExceptionally(new IllegalStateException("Arena chunks took too long to load. Preparation will retry.")), 1200L);
            ready.whenComplete((ignored, failure) -> timeout.cancel());
            for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
                for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
                    ChunkKey key = new ChunkKey(world, x, z);
                    // Paper completes chunk futures on the server thread.
                    requests.add(world.getChunkAtAsync(x, z).thenCompose(chunk -> {
                        if (closed || !accepting.get()) throw new IllegalStateException("Arena chunk loading stopped.");
                        if (chunk == null) throw new IllegalStateException("Could not load an arena chunk.");
                        hold(key, chunk);
                        held.add(key);
                        if (chunk.isEntitiesLoaded()) return CompletableFuture.completedFuture(null);
                        CompletableFuture<Void> entities = new CompletableFuture<>();
                        entityLoads.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entities);
                        waiting.add(new EntityWait(key, entities));
                        return entities;
                    }));
                }
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
                if (failure == null) ready.complete(null);
                else ready.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) { ready.completeExceptionally(failure); }
        return ready.thenCompose(ignored -> {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Arena preparation stopped."));
            return action.get();
        }).whenComplete((ignored, failure) -> {
            accepting.set(false);
            loading.remove(ready);
            for (EntityWait wait : waiting) {
                List<CompletableFuture<Void>> values = entityLoads.get(wait.key());
                if (values != null) {
                    values.remove(wait.ready());
                    if (values.isEmpty()) entityLoads.remove(wait.key());
                }
                wait.ready().cancel(false);
            }
            held.forEach(this::release);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (closed) return;
        Chunk chunk = event.getChunk();
        var waiting = entityLoads.remove(new ChunkKey(event.getWorld(), chunk.getX(), chunk.getZ()));
        if (waiting != null) List.copyOf(waiting).forEach(future -> future.complete(null));
    }

    private void hold(ChunkKey key, Chunk chunk) {
        Ticket ticket = tickets.get(key);
        if (ticket == null) tickets.put(key, new Ticket(chunk, chunk.addPluginChunkTicket(plugin)));
        else ticket.references++;
    }

    private void release(ChunkKey key) {
        Ticket ticket = tickets.get(key);
        if (ticket != null && --ticket.references == 0) {
            tickets.remove(key);
            if (ticket.owned) ticket.chunk.removePluginChunkTicket(plugin);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        for (var request : new ArrayList<>(loading)) request.cancel(false);
        loading.clear();
        entityLoads.clear();
        for (Ticket ticket : new ArrayList<>(tickets.values())) {
            if (ticket.owned) ticket.chunk.removePluginChunkTicket(plugin);
        }
        tickets.clear();
    }
}
