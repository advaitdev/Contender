package me.advait.contender.map;

/** Inclusive block coordinates, with location checks covering each whole block. */
public record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public BlockBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid map bounds");
    }
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < (double) maxX + 1 && y >= minY && y < (double) maxY + 1
                && z >= minZ && z < (double) maxZ + 1;
    }
    public boolean containsColumn(double x, double z) {
        return x >= minX && x < (double) maxX + 1 && z >= minZ && z < (double) maxZ + 1;
    }
    public int width() { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
    public int length() { return maxZ - minZ + 1; }
    public long volume() { return (long) width() * height() * length(); }
    public BlockBounds move(int x, int y, int z) {
        return new BlockBounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }
}
