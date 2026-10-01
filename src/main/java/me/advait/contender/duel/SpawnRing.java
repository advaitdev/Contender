package me.advait.contender.duel;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** Free-for-all spawns: evenly spaced on the circle through both team spawns, facing the middle. */
public final class SpawnRing {
    private SpawnRing() { }

    /** True when there is solid ground a few blocks below, so nobody spawns over the void. */
    private static boolean grounded(Location spawn) {
        if (spawn.getWorld() == null || spawn.getBlock().getType().isSolid()) return false;
        for (int dy = 1; dy <= 6; dy++) {
            if (spawn.clone().subtract(0, dy, 0).getBlock().getType().isSolid()) return true;
        }
        return false;
    }

    public static List<Location> around(Location first, Location second, int count) {
        List<Location> spawns = new ArrayList<>();
        if (count <= 2) {
            spawns.add(first.clone());
            if (count == 2) spawns.add(second.clone());
            return spawns;
        }
        Location center = first.clone().add(second).multiply(0.5);
        center.setWorld(first.getWorld());
        double radius = Math.max(2, first.distance(second) / 2);
        double start = Math.atan2(first.getZ() - center.getZ(), first.getX() - center.getX());
        for (int i = 0; i < count; i++) {
            double angle = start + Math.PI * 2 * i / count;
            Location spawn = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            spawn.setY(i % 2 == 0 ? first.getY() : second.getY());
            Vector facing = center.toVector().subtract(spawn.toVector());
            spawn.setDirection(facing.setY(0).lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : facing);
            spawn.setPitch(0);
            spawns.add(grounded(spawn) ? spawn : (i % 2 == 0 ? first : second).clone());
        }
        return spawns;
    }
}
