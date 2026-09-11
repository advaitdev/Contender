package me.advait.contender.spectator;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Per-viewer spectator visibility for one duel. Call from the server thread. */
public final class SpectatorVisibility {
    private static final double HIDE_DISTANCE_SQUARED = 20.0 * 20.0;

    private final Plugin plugin;
    private final Set<HiddenPlayer> hiddenPlayers = new HashSet<>();

    private record HiddenPlayer(Player viewer, Player spectator) { }

    public SpectatorVisibility(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Call with the duel's online contestants and spectators, including eliminated players. */
    public void update(Collection<Player> contestants, Collection<Player> spectators, boolean alwaysInvisible) {
        Set<HiddenPlayer> desired = new HashSet<>();
        for (Player spectator : spectators) {
            Location location = spectator.getLocation();
            for (Player viewer : contestants) {
                if (viewer.equals(spectator)) continue;
                Location viewerLocation = viewer.getLocation();
                if (alwaysInvisible || location.getWorld().equals(viewerLocation.getWorld())
                        && location.distanceSquared(viewerLocation) < HIDE_DISTANCE_SQUARED) {
                    desired.add(new HiddenPlayer(viewer, spectator));
                }
            }
        }

        // Only send updates when a pair's visibility changes. Retaining the Player
        // reference also lets us clear a viewer's hide entry if a spectator logs out.
        hiddenPlayers.removeIf(hidden -> {
            if (desired.contains(hidden)) return false;
            hidden.viewer().showPlayer(plugin, hidden.spectator());
            return true;
        });
        for (HiddenPlayer hidden : desired) {
            if (hiddenPlayers.add(hidden)) hidden.viewer().hidePlayer(plugin, hidden.spectator());
        }
    }

    public void clear() {
        for (HiddenPlayer hidden : hiddenPlayers) {
            hidden.viewer().showPlayer(plugin, hidden.spectator());
        }
        hiddenPlayers.clear();
    }
}
