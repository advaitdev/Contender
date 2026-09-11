package me.advait.contender.tier;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;

/** Reads the same public profile endpoints as the recovered Tierer plugin. */
public final class MCTiersClient implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();
    public PlayerData byUuid(UUID uuid) throws IOException, InterruptedException { return fetch("/profile/" + uuid); }
    public PlayerData byName(String name) throws IOException, InterruptedException {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Enter a Minecraft player name.");
        return fetch("/profile/by-name/" + name);
    }
    private PlayerData fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://mctiers.com/api/v2" + path))
                .header("Accept", "application/json").header("User-Agent", "Contender/1.0 (Tierer)")
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("MCTiers returned HTTP " + response.statusCode());
        try {
            PlayerData data = gson.fromJson(response.body(), PlayerData.class);
            if (data == null || data.uuid() == null || data.name() == null) throw new JsonParseException("Missing profile identity");
            UUID.fromString(data.uuid());
            return data;
        } catch (JsonParseException | IllegalArgumentException failure) {
            throw new IOException("MCTiers returned an invalid profile", failure);
        }
    }
    @Override public void close() { http.shutdownNow(); }
}
