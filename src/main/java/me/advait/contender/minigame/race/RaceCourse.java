package me.advait.contender.minigame.race;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * A Mace Race course: where racers start and the ordered checkpoints they must hit. The last checkpoint is
 * the finish. Checkpoint mobs are spawned by the race itself, so they can float anywhere, even midair.
 */
public final class RaceCourse {
    public record Checkpoint(double x, double y, double z, float yaw, EntityType type) {
        public Location at(World world) { return new Location(world, x, y, z, yaw, 0); }
    }

    public static final double DEFAULT_RETURN_HEIGHT = 12;
    private final String id;
    private String name;
    private String world;
    private Checkpoint start;
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private int maxJump = 15;
    private double returnHeight = DEFAULT_RETURN_HEIGHT;
    private boolean returnOnGround = true;

    public RaceCourse(String id, String name, String world) {
        this.id = id;
        this.name = name;
        this.world = world;
    }

    public String id() { return id; }
    public String name() { return name; }
    public void name(String value) {
        if (value == null || value.isBlank() || value.length() > 48) throw new IllegalArgumentException("Choose a name up to 48 characters.");
        name = value.strip();
    }
    public String worldName() { return world; }
    public World world() { return Bukkit.getWorld(world); }
    public Checkpoint start() { return start; }
    public List<Checkpoint> checkpoints() { return List.copyOf(checkpoints); }
    public int size() { return checkpoints.size(); }
    public int maxJump() { return maxJump; }
    public double returnHeight() { return returnHeight; }
    public boolean returnOnGround() { return returnOnGround; }

    public Location startLocation() {
        World loaded = world();
        return start == null || loaded == null ? null : new Location(loaded, start.x(), start.y(), start.z(), start.yaw(), 0);
    }

    /** Ready to race: a start and at least two checkpoints (the last is the finish). */
    public String problem() {
        if (start == null) return "Set the start.";
        if (checkpoints.size() < 2) return "Add at least two checkpoints. The last one is the finish.";
        if (world() == null) return "The course's world isn't loaded.";
        return null;
    }

    public void setStart(Location location) {
        requireWorld(location);
        start = new Checkpoint(location.getX(), location.getY(), location.getZ(), location.getYaw(), EntityType.ARMOR_STAND);
    }

    public void add(Location location, EntityType type) { insert(checkpoints.size(), location, type); }

    public void insert(int index, Location location, EntityType type) {
        requireWorld(location);
        if (checkpoints.size() >= 500) throw new IllegalStateException("A course can have up to 500 checkpoints.");
        checkpoints.add(Math.clamp(index, 0, checkpoints.size()), new Checkpoint(location.getX(), location.getY(), location.getZ(), location.getYaw(), type));
    }

    public void move(int index, Location location) {
        requireWorld(location);
        Checkpoint old = checkpoints.get(index);
        checkpoints.set(index, new Checkpoint(location.getX(), location.getY(), location.getZ(), location.getYaw(), old.type()));
    }

    public void retype(int index, EntityType type) {
        Checkpoint old = checkpoints.get(index);
        checkpoints.set(index, new Checkpoint(old.x(), old.y(), old.z(), old.yaw(), type));
    }

    public void remove(int index) { checkpoints.remove(index); }

    public void rules(int maxJump, double returnHeight, boolean returnOnGround) {
        if (maxJump < 1 || maxJump > 500) throw new IllegalArgumentException("Checkpoint jump must be between 1 and 500.");
        if (!Double.isFinite(returnHeight) || returnHeight < 1 || returnHeight > 30) throw new IllegalArgumentException("Return height must be between 1 and 30 blocks.");
        this.maxJump = maxJump;
        this.returnHeight = returnHeight;
        this.returnOnGround = returnOnGround;
    }

    void load(Checkpoint start, List<Checkpoint> checkpoints) {
        this.start = start;
        this.checkpoints.clear();
        this.checkpoints.addAll(checkpoints);
    }

    private void requireWorld(Location location) {
        if (location.getWorld() == null) throw new IllegalArgumentException("Stand in a loaded world.");
        if (checkpoints.isEmpty() && start == null) world = location.getWorld().getName();
        if (!location.getWorld().getName().equals(world)) throw new IllegalArgumentException("This course is in " + world + ". Edit it from that world.");
    }

    /** Label for checkpoint {@code index} (0-based): "#1", "#2" … and "Finish" for the last. */
    public String label(int index) { return index == checkpoints.size() - 1 ? "Finish" : "#" + (index + 1); }
}
