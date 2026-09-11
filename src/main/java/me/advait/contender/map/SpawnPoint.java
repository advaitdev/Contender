package me.advait.contender.map;

import org.bukkit.Location;
import org.bukkit.World;

public record SpawnPoint(double x, double y, double z, float yaw, float pitch) {
    public static SpawnPoint from(Location location) {
        return new SpawnPoint(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }
    public Location in(World world) { return new Location(world, x, y, z, yaw, pitch); }
    public SpawnPoint move(int dx, int dy, int dz) { return new SpawnPoint(x + dx, y + dy, z + dz, yaw, pitch); }
}
