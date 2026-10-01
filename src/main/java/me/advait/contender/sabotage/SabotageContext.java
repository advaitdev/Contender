package me.advait.contender.sabotage;

import me.advait.contender.Contender;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** What a running sabotage can see: the plugin, its own settings, and who it affects. */
public final class SabotageContext {
    private final Contender plugin;
    private final ConfigurationSection settings;
    private final Predicate<UUID> affected;

    SabotageContext(Contender plugin, ConfigurationSection settings, Predicate<UUID> affected) {
        this.plugin = plugin;
        this.settings = settings == null ? new MemoryConfiguration() : settings;
        this.affected = affected;
    }

    public Contender plugin() { return plugin; }
    public ConfigurationSection settings() { return settings; }
    public boolean affects(Player player) { return player != null && affected.test(player.getUniqueId()); }
    public boolean affects(UUID player) { return affected.test(player); }

    public List<Player> affectedPlayers() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) if (affects(player)) players.add(player);
        return players;
    }

    public Set<UUID> online() {
        Set<UUID> ids = new java.util.HashSet<>();
        for (Player player : affectedPlayers()) ids.add(player.getUniqueId());
        return ids;
    }
}
