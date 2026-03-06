package me.advait.contender;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SpectatorManager {

    private final Contender plugin;
    private final File file;
    private final Set<UUID> eventSpectators = new HashSet<>();

    public SpectatorManager(Contender plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spectators.yml");
        load();
    }

    public boolean isEventSpectator(UUID uuid) {
        return eventSpectators.contains(uuid);
    }

    public boolean isEventSpectator(Player player) {
        return isEventSpectator(player.getUniqueId());
    }

    public void addEventSpectator(UUID uuid) {
        eventSpectators.add(uuid);
        save();
    }

    public void removeEventSpectator(UUID uuid) {
        eventSpectators.remove(uuid);
        save();
    }

    public Set<UUID> getEventSpectators() {
        return Collections.unmodifiableSet(eventSpectators);
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String s : config.getStringList("spectators")) {
            try {
                eventSpectators.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<String> uuids = new ArrayList<>();
        for (UUID uuid : eventSpectators) {
            uuids.add(uuid.toString());
        }
        config.set("spectators", uuids);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save spectators.yml: " + e.getMessage());
        }
    }
}
