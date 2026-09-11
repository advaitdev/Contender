package me.advait.contender.tier;

import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Nonblocking, coalesced lookups. Cache and callback bookkeeping runs on the server thread. */
public final class TierService implements AutoCloseable {
    @FunctionalInterface interface Lookup { PlayerData fetch(String key) throws Exception; }
    private record Cached(PlayerData data, long expiresAt, Throwable failure) { }
    private final Plugin plugin;
    private final MCTiersClient client;
    private final Lookup lookup;
    private final LongSupplier clock;
    private final ExecutorService executor;
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, CompletableFuture<PlayerData>> pending = new HashMap<>();
    private volatile boolean closed;

    public TierService(Plugin plugin) {
        this.plugin = plugin;
        client = new MCTiersClient();
        lookup = key -> key.startsWith("u:") ? client.byUuid(UUID.fromString(key.substring(2))) : client.byName(key.substring(2));
        clock = System::currentTimeMillis;
        executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("Contender-tiers-", 0).factory());
    }
    TierService(Plugin plugin, Lookup lookup, LongSupplier clock, ExecutorService executor) {
        this.plugin = plugin; this.lookup = lookup; this.clock = clock; this.executor = executor; client = null;
    }
    public PlayerData cached(UUID uuid) {
        Cached entry = cache.get("u:" + uuid);
        return entry == null ? null : entry.data();
    }
    public CompletableFuture<PlayerData> refresh(UUID uuid) { return request("u:" + uuid); }
    public CompletableFuture<PlayerData> byName(String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return CompletableFuture.failedFuture(new IllegalArgumentException("Enter a Minecraft player name."));
        return request("n:" + name.toLowerCase(Locale.ROOT));
    }
    private CompletableFuture<PlayerData> request(String key) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Tier lookups have stopped."));
        Cached old = cache.get(key);
        if (old != null && old.expiresAt() > clock.getAsLong()) {
            return old.failure() == null ? CompletableFuture.completedFuture(old.data()) : CompletableFuture.failedFuture(old.failure());
        }
        if (pending.containsKey(key)) return pending.get(key);
        CompletableFuture<PlayerData> result = new CompletableFuture<>();
        pending.put(key, result);
        executor.execute(() -> {
            PlayerData data = null;
            Throwable failure = null;
            try { data = lookup.fetch(key); }
            catch (Exception error) { failure = error; if (error instanceof InterruptedException) Thread.currentThread().interrupt(); }
            PlayerData response = data;
            Throwable error = failure;
            if (closed || !plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> complete(key, old, response, error, result));
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Plugin stopped between the check and scheduling. */ }
        });
        return result;
    }
    private void complete(String key, Cached old, PlayerData data, Throwable failure, CompletableFuture<PlayerData> result) {
        pending.remove(key);
        if (closed) return;
        long now = clock.getAsLong();
        // Bound offline /tier lookups without evicting in-flight work.
        if (cache.size() >= 2048) cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        if (cache.size() >= 2048) cache.remove(cache.keySet().iterator().next());
        if (failure == null) {
            Cached entry = new Cached(data, now + 300_000, null);
            cache.put(key, entry);
            if (data != null) {
                cache.put("u:" + data.uuid(), entry);
                cache.put("n:" + data.name().toLowerCase(Locale.ROOT), entry);
            }
            result.complete(data);
        } else {
            cache.put(key, new Cached(old == null ? null : old.data(), now + 60_000, failure));
            plugin.getLogger().warning("Could not refresh a tier profile: " + failure.getMessage());
            result.completeExceptionally(failure);
        }
    }
    @Override public void close() {
        closed = true;
        executor.shutdownNow();
        if (client != null) client.close();
        for (var request : new ArrayList<>(pending.values())) request.cancel(false);
        pending.clear();
        cache.clear();
    }
}
