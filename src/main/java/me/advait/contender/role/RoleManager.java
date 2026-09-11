package me.advait.contender.role;

import me.advait.contender.Contender;
import me.advait.contender.util.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoleManager {
    private final Contender plugin;
    private final File file;
    private final Map<UUID, PlayerRole> roles = new ConcurrentHashMap<>();

    public RoleManager(Contender plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "roles.yml");
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        var section = saved.getConfigurationSection("players");
        if (section != null) for (String key : section.getKeys(false)) {
            try { roles.put(UUID.fromString(key), PlayerRole.parse(section.getString(key, "contestant"))); }
            catch (IllegalArgumentException failure) { plugin.getLogger().warning("Invalid role entry in roles.yml: " + key); }
        }
    }
    public PlayerRole getRole(UUID player) { return roles.getOrDefault(player, PlayerRole.CONTESTANT); }
    public boolean isContestant(UUID player) { return getRole(player) == PlayerRole.CONTESTANT; }

    public void setRole(UUID player, PlayerRole role) {
        java.util.Objects.requireNonNull(role);
        if (getRole(player) == role) return;
        if (role != PlayerRole.CONTESTANT) {
            if (plugin.getDuelManager().isPlaying(player)) throw new IllegalStateException("End this player's duel before changing their role.");
            if (plugin.getTournamentManager().isReserved(player)) throw new IllegalStateException("Finish or cancel this player's tournament before changing their role.");
        }
        YamlConfiguration saved = new YamlConfiguration();
        roles.forEach((id, value) -> saved.set("players." + id, value.id()));
        saved.set("players." + player, role.id());
        YamlStorage.save(saved, file);
        roles.put(player, role);
        plugin.getNameTagManager().refresh();
    }
}
