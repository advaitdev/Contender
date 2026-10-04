package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;

/** Places copies on a grid: 100 slots per row, each slot a square column of the configured spacing. */
public final class ArenaLayout {
    public static final int MARGIN = 32;
    private ArenaLayout() { }

    public static ArenaCopy place(ArenaMap template, String world, int slot, int cellSize, int minHeight, int maxHeight) {
        validate(template, cellSize, minHeight, maxHeight);
        if (slot < 0) throw new IllegalArgumentException("Invalid arena slot.");
        int x = Math.multiplyExact(slot % 100, cellSize);
        int z = Math.multiplyExact(slot / 100, cellSize);
        BlockBounds bounds = template.getBounds();
        ArenaMap copy = template.translated(world, x + MARGIN - bounds.minX(), 0, z + MARGIN - bounds.minZ());
        return new ArenaCopy(slot, copy, new BlockBounds(x, minHeight, z, x + cellSize - 1, maxHeight - 1, z + cellSize - 1));
    }

    public static ArenaMap translate(ArenaMap template, ArenaCopy copy) {
        BlockBounds bounds = template.getBounds();
        return template.translated(copy.layout().getWorldName(), copy.cell().minX() + MARGIN - bounds.minX(), 0,
                copy.cell().minZ() + MARGIN - bounds.minZ());
    }

    public static void validate(ArenaMap template, int cellSize, int minHeight, int maxHeight) {
        BlockBounds bounds = template.getBounds();
        if (bounds == null) throw new IllegalArgumentException("Select the map region first.");
        if (!template.isComplete()) throw new IllegalArgumentException("Set both team spawns before preparing this map.");
        if (bounds.width() > cellSize - 2 * MARGIN || bounds.length() > cellSize - 2 * MARGIN) {
            throw new IllegalArgumentException("This map is wider than the arena spacing allows (" + (cellSize - 2 * MARGIN) + " blocks).");
        }
        if (bounds.minY() < minHeight || bounds.maxY() >= maxHeight) {
            throw new IllegalArgumentException("The selected region extends beyond the arena world's build height.");
        }
    }
}
