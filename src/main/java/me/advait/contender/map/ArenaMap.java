package me.advait.contender.map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** A saved map template, or the translated layout used by one arena copy. */
public class ArenaMap {
    private final String id;
    private String displayName;
    private String worldName = "world";
    private SpawnPoint team1Spawn;
    private SpawnPoint team2Spawn;
    private SpawnPoint spectatorSpawn;
    private BlockBounds bounds;
    private String schematic;
    private int copies = 20;
    private int firstSlot = -1;

    public ArenaMap(String id) {
        this.id = id;
        this.displayName = id;
    }
    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }
    public SpawnPoint getTeam1Point() { return team1Spawn; }
    public SpawnPoint getTeam2Point() { return team2Spawn; }
    public SpawnPoint getSpectatorPoint() { return spectatorSpawn; }
    public BlockBounds getBounds() { return bounds; }
    public String getSchematic() { return schematic; }
    public void setSchematic(String schematic) { this.schematic = schematic; }
    public int getCopies() { return copies; }
    public void setCopies(int copies) {
        if (copies < 1 || copies > 100) throw new IllegalArgumentException("Choose between 1 and 100 arena copies.");
        this.copies = copies;
    }
    public int getFirstSlot() { return firstSlot; }
    public void setFirstSlot(int firstSlot) { this.firstSlot = firstSlot; }

    // Worlds are prepared by ArenaManager at startup, never by a spawn lookup.
    private Location location(SpawnPoint point) {
        World world = Bukkit.getWorld(worldName);
        return world == null || point == null ? null : point.in(world);
    }
    public Location getTeam1Spawn() { return location(team1Spawn); }
    public Location getTeam2Spawn() { return location(team2Spawn); }
    public Location getSpectatorSpawn() { return location(spectatorSpawn == null ? team1Spawn : spectatorSpawn); }
    public void setTeam1Spawn(double x, double y, double z, float yaw, float pitch) {
        team1Spawn = new SpawnPoint(x, y, z, yaw, pitch);
    }
    public void setTeam2Spawn(double x, double y, double z, float yaw, float pitch) {
        team2Spawn = new SpawnPoint(x, y, z, yaw, pitch);
    }
    public void setSpectatorSpawn(double x, double y, double z, float yaw, float pitch) {
        spectatorSpawn = new SpawnPoint(x, y, z, yaw, pitch);
    }
    public boolean hasRollbackRegion() { return bounds != null; }
    public double getCorner1X() { return bounds == null ? 0 : bounds.minX(); }
    public double getCorner1Y() { return bounds == null ? 0 : bounds.minY(); }
    public double getCorner1Z() { return bounds == null ? 0 : bounds.minZ(); }
    public double getCorner2X() { return bounds == null ? 0 : bounds.maxX(); }
    public double getCorner2Y() { return bounds == null ? 0 : bounds.maxY(); }
    public double getCorner2Z() { return bounds == null ? 0 : bounds.maxZ(); }
    public void setRollbackRegion(double x1, double y1, double z1, double x2, double y2, double z2) {
        bounds = new BlockBounds((int) Math.floor(Math.min(x1, x2)), (int) Math.floor(Math.min(y1, y2)),
                (int) Math.floor(Math.min(z1, z2)), (int) Math.floor(Math.max(x1, x2)),
                (int) Math.floor(Math.max(y1, y2)), (int) Math.floor(Math.max(z1, z2)));
    }
    public boolean isComplete() { return bounds != null && team1Spawn != null && team2Spawn != null; }
    public boolean contains(Location location) {
        return bounds != null && location.getWorld() != null && worldName.equals(location.getWorld().getName())
                && bounds.contains(location.getX(), location.getY(), location.getZ());
    }
    public ArenaMap translated(String world, int dx, int dy, int dz) {
        ArenaMap copy = new ArenaMap(id);
        copy.displayName = displayName;
        copy.worldName = world;
        copy.bounds = bounds.move(dx, dy, dz);
        copy.team1Spawn = team1Spawn.move(dx, dy, dz);
        copy.team2Spawn = team2Spawn.move(dx, dy, dz);
        copy.spectatorSpawn = (spectatorSpawn == null ? team1Spawn : spectatorSpawn).move(dx, dy, dz);
        return copy;
    }
}
