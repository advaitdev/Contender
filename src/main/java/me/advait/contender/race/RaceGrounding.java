package me.advait.contender.race;

import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Detect block landings without counting checkpoint mobs or trusting the client's on-ground flag. */
final class RaceGrounding {
    private static final double CONTACT = 0.03125;
    private static final double EPSILON = 1.0e-6;
    private final Set<UUID> airborne = new HashSet<>();

    boolean landed(UUID id, boolean supported) {
        if (!supported) { airborne.add(id); return false; }
        // Keep this armed until a successful teleport, so a blocked return can be retried.
        return airborne.contains(id);
    }
    void reset(UUID id) { airborne.remove(id); }
    void clear() { airborne.clear(); }

    static boolean supported(World world, BoundingBox player) {
        double feet = player.getMinY();
        BoundingBox sole = new BoundingBox(player.getMinX() + EPSILON, feet - CONTACT, player.getMinZ() + EPSILON,
                player.getMaxX() - EPSILON, feet + EPSILON, player.getMaxZ() - EPSILON);
        int minY = Math.max(world.getMinHeight(), floor(feet - CONTACT) - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, floor(feet));
        for (int x = floor(sole.getMinX()); x <= floor(sole.getMaxX()); x++) {
            for (int z = floor(sole.getMinZ()); z <= floor(sole.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    // Shapes are relative to their block. Include the block below to cover fences and walls.
                    for (BoundingBox shape : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
                        BoundingBox collision = shape.clone().shift(x, y, z);
                        if (collision.getMaxY() <= feet + EPSILON && collision.overlaps(sole)) return true;
                    }
                }
            }
        }
        return false;
    }
    private static int floor(double value) { return (int) Math.floor(value); }
}
