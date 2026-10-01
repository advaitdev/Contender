package me.advait.contender.spectate;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

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

    /** The spectator spawn, turned to face {@link #focus()}. */
    default Location watchSpot() {
        Location spawn = spectatorSpawn();
        Location focus = focus();
        if (spawn == null || focus == null || focus.getWorld() != spawn.getWorld() || focus.distanceSquared(spawn) <= 1) return spawn;
        return facing(spawn, focus, this::contains);
    }

    /**
     * Turns a spot to face the action. A spawn set right above the arena would look straight down,
     * so it steps back through open air until the view is about 50 degrees down.
     */
    static Location facing(Location spawn, Location focus, Predicate<Location> allowed) {
        Location spot = spawn.clone();
        double drop = spot.getY() + EYE_HEIGHT - focus.getY();
        double flat = Math.hypot(focus.getX() - spot.getX(), focus.getZ() - spot.getZ());
        double wanted = drop / Math.tan(Math.toRadians(50));
        if (drop > 3 && flat < wanted - 1) {
            Vector back = flat > 0.5 ? new Vector(spot.getX() - focus.getX(), 0, spot.getZ() - focus.getZ())
                    : new Vector(-Math.sin(Math.toRadians(spawn.getYaw())), 0, Math.cos(Math.toRadians(spawn.getYaw()))).multiply(-1);
            back.normalize();
            for (double step = wanted - flat; step >= 1; step -= 1) {
                Location candidate = spawn.clone().add(back.clone().multiply(step));
                if (allowed.test(candidate) && open(candidate)) { spot = candidate; break; }
            }
        }
        return spot.setDirection(focus.toVector().subtract(spot.toVector().setY(spot.getY() + EYE_HEIGHT)));
    }

    private static boolean open(Location location) {
        // Watchers are often placed before anyone is near, so load the chunk, but never generate one.
        if (!location.getWorld().isChunkGenerated(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        for (int y = 0; y <= 1; y++) if (!location.clone().add(0, y, 0).getBlock().isPassable()) return false;
        return true;
    }

    double EYE_HEIGHT = 1.62;

    /** Hide watcher avatars from competitors entirely. */
    default boolean hidesAvatars() { return false; }
}
