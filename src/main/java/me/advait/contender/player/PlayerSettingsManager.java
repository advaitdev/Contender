package me.advait.contender.player;

import me.advait.contender.Contender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerSettingsManager {

    public enum SpectatorDisguise {
        ALLAY, BEE, PARROT, BAT, VEX, HAPPY_GHAST
    }

    private final Contender plugin;
    private final File file;
    private final Map<UUID, SpectatorDisguise> disguisePreferences = new HashMap<>();

    public PlayerSettingsManager(Contender plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "player_settings.yml");
        load();
    }

    public SpectatorDisguise getDisguise(UUID uuid) {
        return disguisePreferences.getOrDefault(uuid, SpectatorDisguise.ALLAY);
    }

    public void setDisguise(UUID uuid, SpectatorDisguise type) {
        disguisePreferences.put(uuid, type);
        save();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.contains("disguises")) return;
        for (String key : config.getConfigurationSection("disguises").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                SpectatorDisguise type = SpectatorDisguise.valueOf(
                        config.getString("disguises." + key, "ALLAY").toUpperCase());
                disguisePreferences.put(uuid, type);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, SpectatorDisguise> entry : disguisePreferences.entrySet()) {
            config.set("disguises." + entry.getKey().toString(), entry.getValue().name());
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save player_settings.yml: " + e.getMessage());
        }
    }
}
