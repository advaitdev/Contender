package me.advait.contender.spectator;

import me.advait.contender.Contender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SpectatorManager {

    private final Contender plugin;
    private final File file;
    private final Set<UUID> deceased = new HashSet<>();

    public SpectatorManager(Contender plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spectators.yml");
        load();
    }

    public boolean isDeceased(UUID uuid) {
        return deceased.contains(uuid);
    }

    public boolean isDeceased(Player player) {
        return isDeceased(player.getUniqueId());
    }

    public void addDeceased(UUID uuid) {
        deceased.add(uuid);
        save();
    }

    public void removeDeceased(UUID uuid) {
        deceased.remove(uuid);
        save();
    }

    public Set<UUID> getDeceased() {
        return Collections.unmodifiableSet(deceased);
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String s : config.getStringList("spectators")) {
            try {
                deceased.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<String> uuids = new ArrayList<>();
        for (UUID uuid : deceased) {
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
