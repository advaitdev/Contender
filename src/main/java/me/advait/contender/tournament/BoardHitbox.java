package me.advait.contender.tournament;

import org.bukkit.util.Vector;

/** Board-local ray intersection; coordinates are in blocks, x right and y up. */
public final class BoardHitbox {
    private BoardHitbox() { }
    public record Hit(double x, double y, double distance) { }
    public static Hit intersect(Vector origin, Vector direction, Vector anchor, Vector normal, Vector right, double reach) {
        double denominator = direction.dot(normal);
        if (denominator >= -0.001) return null; // Only the front of the board can be selected.
        double distance = anchor.clone().subtract(origin).dot(normal) / denominator;
        if (distance < 0 || distance > reach) return null;
        Vector offset = origin.clone().add(direction.clone().multiply(distance)).subtract(anchor);
        return new Hit(offset.dot(right), offset.getY(), distance);
    }
    public static boolean contains(Hit hit, double x, double y, double width, double height) {
        return hit != null && Math.abs(hit.x() - x) <= width / 2 && Math.abs(hit.y() - y) <= height / 2;
    }
}
