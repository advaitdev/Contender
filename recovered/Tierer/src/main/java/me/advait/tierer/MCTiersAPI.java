/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  org.bukkit.plugin.Plugin
 */
package me.advait.tierer;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.advait.tierer.PlayerData;
import org.bukkit.plugin.Plugin;

public class MCTiersAPI {
    private static final String BASE = "https://mctiers.com/api/v2";
    private static final long CACHE_TTL_MS = 300000L;
    private final Plugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private final Map<UUID, CachedEntry> cache = new ConcurrentHashMap<UUID, CachedEntry>();
    private final Set<UUID> fetching = ConcurrentHashMap.newKeySet();

    public MCTiersAPI(Plugin plugin) {
        this.plugin = plugin;
    }

    public PlayerData getCached(UUID uuid) {
        CachedEntry entry = this.cache.get(uuid);
        return entry != null ? entry.data : null;
    }

    public void ensureFresh(UUID uuid, Runnable onDone) {
        CachedEntry entry = this.cache.get(uuid);
        if (entry != null && !entry.isStale()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (!this.fetching.add(uuid)) {
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                PlayerData data = this.fetchByUUID(uuid);
                if (data != null) {
                    this.cache.put(uuid, new CachedEntry(data));
                }
            }
            catch (Exception e) {
                this.plugin.getLogger().warning("[Tierer] Failed to fetch data for " + String.valueOf(uuid) + ": " + e.getMessage());
            }
            finally {
                this.fetching.remove(uuid);
            }
            if (onDone != null) {
                this.plugin.getServer().getScheduler().runTask(this.plugin, onDone);
            }
        });
    }

    public PlayerData fetchByUUID(UUID uuid) throws Exception {
        return this.fetch("https://mctiers.com/api/v2/profile/" + String.valueOf(uuid));
    }

    public PlayerData fetchByName(String name) throws Exception {
        PlayerData data = this.fetch("https://mctiers.com/api/v2/profile/by-name/" + name);
        if (data != null && data.getUuid() != null) {
            try {
                UUID uuid = UUID.fromString(data.getUuid());
                this.cache.put(uuid, new CachedEntry(data));
            }
            catch (IllegalArgumentException illegalArgumentException) {
                // empty catch block
            }
        }
        return data;
    }

    private PlayerData fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "application/json").header("User-Agent", "Tierer-Plugin/1.0").GET().build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("MCTiers API returned HTTP " + response.statusCode());
        }
        return (PlayerData)this.gson.fromJson(response.body(), PlayerData.class);
    }

    private static class CachedEntry {
        final PlayerData data;
        final long timestamp;

        CachedEntry(PlayerData data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isStale() {
            return System.currentTimeMillis() - this.timestamp > 300000L;
        }
    }
}
