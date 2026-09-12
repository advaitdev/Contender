package me.advait.contender.role;

import me.advait.contender.Contender;
import me.advait.contender.util.YamlStorage;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

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
        if (!saved.getBoolean("legacy-spectators-imported")) {
            var legacy = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "spectators.yml"));
            for (String value : legacy.getStringList("spectators")) {
                try {
                    UUID id = UUID.fromString(value);
                    if (getRole(id) != PlayerRole.DIRECTOR) roles.put(id, PlayerRole.SPECTATOR);
                } catch (IllegalArgumentException failure) { plugin.getLogger().warning("Invalid legacy spectator UUID: " + value); }
            }
            // Keep the old file as a backup; this marker prevents importing it again after a role changes.
            save(roles);
        }
    }
    public PlayerRole getRole(UUID player) { return roles.getOrDefault(player, PlayerRole.CONTESTANT); }
    public boolean isContestant(UUID player) { return getRole(player) == PlayerRole.CONTESTANT; }

    private void save(Map<UUID, PlayerRole> assignments) {
        YamlConfiguration saved = new YamlConfiguration();
        saved.set("legacy-spectators-imported", true);
        assignments.forEach((id, value) -> saved.set("players." + id, value.id()));
        YamlStorage.save(saved, file);
    }

    public void setRole(UUID id, PlayerRole role) {
        java.util.Objects.requireNonNull(role);
        PlayerRole previous = getRole(id);
        if (previous != role) {
            var updated = new java.util.HashMap<>(roles);
            updated.put(id, role);
            save(updated);
            roles.put(id, role);
        }
        var duels = plugin.getDuelManager();
        var tournaments = plugin.getTournamentManager();
        var duel = duels.getDuel(id);
        if (role != PlayerRole.CONTESTANT) {
            if (plugin.getMinigameManager() != null) plugin.getMinigameManager().withdraw(id);
            else if (plugin.getRaceManager() != null) plugin.getRaceManager().withdraw(id);
            // Preserve the roster and results for the director to review or reroll.
            if (tournaments.isReserved(id)) tournaments.pause();
            if (duel != null && duel.isInDuel(id)) tournaments.cancelDuel(duel);
            var votes = plugin.getVoteManager();
            if (votes != null && votes.getActiveSession() != null) {
                votes.getActiveSession().removeVote(id);
                votes.getActiveSession().removeCandidate(id);
            }
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            player.updateCommands();
            if (duel != null && !duel.isInDuel(id) && (role == PlayerRole.SPECTATOR || previous == PlayerRole.SPECTATOR)) {
                duels.leaveSpectating(player);
            }
            if (role == PlayerRole.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
            else if (previous == PlayerRole.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        }
        plugin.getNameTagManager().refresh();
        plugin.refreshVoiceRouting();
        plugin.refreshHackerAttributes();
        if (plugin.getTabManager() != null) plugin.getTabManager().refresh();
    }

    public void applySpectatorRole(Player player) {
        if (getRole(player.getUniqueId()) == PlayerRole.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
    }
}
