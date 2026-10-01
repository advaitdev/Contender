package me.advait.contender.spectate;

import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/** Something that can be watched: a match or a minigame. */
public interface Spectatable {
    String displayName();

    /** Where watchers arrive. */
    Location spectatorSpawn();

    /** Watchers stay inside this area. */
    boolean contains(Location location);

    boolean acceptsWatchers();

    void addWatcher(UUID player);

    void removeWatcher(UUID player);

    /** Players competing, who see watchers' avatars only from a distance. */
    Set<UUID> players();

    /** What watchers face when they arrive, or null to keep the spawn's facing. */
    default Location focus() { return null; }

    /** Hide watcher avatars from competitors entirely. */
    default boolean hidesAvatars() { return false; }
}
