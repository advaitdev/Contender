package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import org.bukkit.Location;

/** One pasted copy of a map in the arena world. State changes happen on the server thread. */
public final class ArenaCopy {
    public enum Status { WAITING, PASTING, READY, IN_USE, FAILED }

    private final int slot;
    private final BlockBounds cell;
    private ArenaMap layout;
    private Status status = Status.WAITING;
    private String pastedSchematic;
    private boolean dirty;
    private boolean retired;
    private String failure;
    private long retryAfter;
    private int failures;
    private ArenaActivity user;

    ArenaCopy(int slot, ArenaMap layout, BlockBounds cell) {
        this.slot = slot;
        this.layout = layout;
        this.cell = cell;
    }

    public int slot() { return slot; }
    public String mapId() { return layout.getId(); }
    /** The map translated into this copy's slot: bounds and spawns are in arena-world coordinates. */
    public ArenaMap layout() { return layout; }
    /** The whole column reserved for this copy, including the empty margin around the map. */
    public BlockBounds cell() { return cell; }
    public Status status() { return status; }
    public boolean isDirty() { return dirty; }
    public String failure() { return failure; }
    /** The game currently using this copy, or null when it is idle. */
    public ArenaActivity user() { return user; }
    void user(ArenaActivity user) { this.user = user; }

    public boolean contains(Location location) {
        return location != null && location.getWorld() != null && location.getWorld().getName().equals(layout.getWorldName())
                && cell.containsColumn(location.getX(), location.getZ());
    }

    public boolean insideMap(Location location) { return layout.contains(location); }

    /** Blocks changed since the last paste, so the next reset must paste the template again. */
    public void markDirty() { dirty = true; }

    // Package-private transitions, driven by ArenaService.
    void status(Status status) { this.status = status; }
    void relayout(ArenaMap layout) { this.layout = layout; }
    String pastedSchematic() { return pastedSchematic; }
    void pasted(String schematic) {
        pastedSchematic = schematic;
        dirty = false;
        failure = null;
        failures = 0;
    }
    void failed(String reason, long now) {
        failure = reason;
        failures++;
        status = Status.FAILED;
        retryAfter = now + Math.min(60_000L, 5_000L << Math.min(failures - 1, 4));
    }
    boolean retryDue(long now) { return status != Status.FAILED || now >= retryAfter; }
    boolean retired() { return retired; }
    void retire() { retired = true; }
}
