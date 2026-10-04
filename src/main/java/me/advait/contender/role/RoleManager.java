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

    /**
     * Changes a role. A contestant who stops being one leaves whatever they are playing: a running match is
     * cancelled (a tournament match goes back into the queue), a minigame withdraws them, and they stop being
     * a vote candidate.
     */
    public void setRole(UUID id, PlayerRole role) {
        java.util.Objects.requireNonNull(role);
        PlayerRole previous = getRole(id);
        if (previous != role) {
            var updated = new java.util.HashMap<>(roles);
            updated.put(id, role);
            save(updated);
            roles.put(id, role);
        }
        if (role != PlayerRole.CONTESTANT) {
            var duel = plugin.getDuels().duelOf(id);
            if (duel != null && !plugin.getTournaments().cancelMatch(duel)) duel.forceStop("A player's role changed.");
            var game = plugin.getMinigames().current();
            if (game != null && game.involves(id)) game.withdraw(id);
            var tournament = plugin.getTournaments().current();
            if (tournament != null && tournament.isRunning() && tournament.isReserved(id)) plugin.getTournaments().pause();
            if (plugin.getVotes().isActive()) {
                plugin.getVotes().session().unvote(id);
                if (plugin.getVotes().session().candidate(id) != null) plugin.getVotes().removeCandidate(id);
            }
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            player.updateCommands();
            if (plugin.getRegistry().isFree(id)) {
                if (role == PlayerRole.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
                else if (previous == PlayerRole.SPECTATOR && player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
            }
        }
        plugin.getNameTagManager().refresh();
        plugin.getHackers().refresh();
        plugin.getStages().refreshDisplays();
    }

    public void applySpectatorRole(Player player) {
        if (getRole(player.getUniqueId()) == PlayerRole.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
    }
}
