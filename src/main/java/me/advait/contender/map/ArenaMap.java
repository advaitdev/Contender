package me.advait.contender.map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class ArenaMap {

    private final String id;
    private String displayName;
    private String worldName;
    private double team1X, team1Y, team1Z;
    private float team1Yaw, team1Pitch;
    private double team2X, team2Y, team2Z;
    private float team2Yaw, team2Pitch;
    private double spectatorX, spectatorY, spectatorZ;
    private float spectatorYaw, spectatorPitch;
    private double corner1X, corner1Y, corner1Z;
    private double corner2X, corner2Y, corner2Z;
    private boolean hasRollbackRegion;

    public ArenaMap(String id) {
        this.id = id;
        this.displayName = id;
        this.worldName = "world";
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public Location getTeam1Spawn() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, team1X, team1Y, team1Z, team1Yaw, team1Pitch);
    }

    public void setTeam1Spawn(double x, double y, double z, float yaw, float pitch) {
        this.team1X = x;
        this.team1Y = y;
        this.team1Z = z;
        this.team1Yaw = yaw;
        this.team1Pitch = pitch;
    }

    public Location getTeam2Spawn() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, team2X, team2Y, team2Z, team2Yaw, team2Pitch);
    }

    public void setTeam2Spawn(double x, double y, double z, float yaw, float pitch) {
        this.team2X = x;
        this.team2Y = y;
        this.team2Z = z;
        this.team2Yaw = yaw;
        this.team2Pitch = pitch;
    }

    public Location getSpectatorSpawn() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, spectatorX, spectatorY, spectatorZ, spectatorYaw, spectatorPitch);
    }

    public void setSpectatorSpawn(double x, double y, double z, float yaw, float pitch) {
        this.spectatorX = x;
        this.spectatorY = y;
        this.spectatorZ = z;
        this.spectatorYaw = yaw;
        this.spectatorPitch = pitch;
    }

    public boolean hasRollbackRegion() {
        return hasRollbackRegion;
    }

    public double getCorner1X() { return corner1X; }
    public double getCorner1Y() { return corner1Y; }
    public double getCorner1Z() { return corner1Z; }
    public double getCorner2X() { return corner2X; }
    public double getCorner2Y() { return corner2Y; }
    public double getCorner2Z() { return corner2Z; }

    public void setRollbackRegion(double c1x, double c1y, double c1z, double c2x, double c2y, double c2z) {
        this.corner1X = c1x;
        this.corner1Y = c1y;
        this.corner1Z = c1z;
        this.corner2X = c2x;
        this.corner2Y = c2y;
        this.corner2Z = c2z;
        this.hasRollbackRegion = true;
    }
}
