package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;

public final class ArenaLayout {
    private ArenaLayout() { }
    public static ArenaInstance place(ArenaMap template, String world, int slot, int cellSize,
                                      int minHeight, int maxHeight) {
        BlockBounds bounds = template.getBounds();
        if (!template.isComplete()) throw new IllegalArgumentException("Set both team spawns before preparing this map.");
        if (slot < 0 || cellSize < 128 || bounds.width() > cellSize - 64 || bounds.length() > cellSize - 64) {
            throw new IllegalArgumentException("This map is too wide for the arena spacing in config.yml.");
        }
        if (bounds.minY() < minHeight || bounds.maxY() >= maxHeight) {
            throw new IllegalArgumentException("The selected region extends beyond the arena world's build height.");
        }
        int x = Math.multiplyExact(slot % 100, cellSize);
        int z = Math.multiplyExact(slot / 100, cellSize);
        ArenaMap copy = template.translated(world, x + 32 - bounds.minX(), 0, z + 32 - bounds.minZ());
        return new ArenaInstance(slot, copy, new BlockBounds(x, minHeight, z, x + cellSize - 1,
                maxHeight - 1, z + cellSize - 1));
    }
}
