package me.advait.contender.pvp;

import me.advait.contender.Contender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class PvPSettings {

    private final Contender plugin;
    private final File file;

    private boolean allowLobbyPvp = false;
    private boolean allowNonDuelWorldPvp = true;
    private boolean adminPvpOverride = true;

    public PvPSettings(Contender plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pvp_settings.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            save();
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        allowLobbyPvp = config.getBoolean("allow-lobby-pvp", false);
        allowNonDuelWorldPvp = config.getBoolean("allow-non-duel-world-pvp", true);
        adminPvpOverride = config.getBoolean("admin-pvp-override", true);
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("allow-lobby-pvp", allowLobbyPvp);
        config.set("allow-non-duel-world-pvp", allowNonDuelWorldPvp);
        config.set("admin-pvp-override", adminPvpOverride);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save pvp_settings.yml: " + e.getMessage());
        }
    }

    public boolean isAllowLobbyPvp() { return allowLobbyPvp; }
    public void setAllowLobbyPvp(boolean v) { this.allowLobbyPvp = v; }

    public boolean isAllowNonDuelWorldPvp() { return allowNonDuelWorldPvp; }
    public void setAllowNonDuelWorldPvp(boolean v) { this.allowNonDuelWorldPvp = v; }

    public boolean isAdminPvpOverride() { return adminPvpOverride; }
    public void setAdminPvpOverride(boolean v) { this.adminPvpOverride = v; }
}
