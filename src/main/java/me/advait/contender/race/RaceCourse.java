package me.advait.contender.race;

/** A course uses the map's first team spawn and the entities in its saved schematic. */
public record RaceCourse(String mapId, int maxAdvance, String finishName, double returnHeight, boolean returnOnGround) {
    public static final double DEFAULT_RETURN_HEIGHT = 12;
    public RaceCourse(String mapId, int maxAdvance, String finishName, double returnHeight) {
        this(mapId, maxAdvance, finishName, returnHeight, true);
    }
    public RaceCourse {
        if (maxAdvance < 1 || maxAdvance > 1000) throw new IllegalArgumentException("Checkpoint jump must be between 1 and 1,000.");
        if (finishName == null || finishName.isBlank() || finishName.matches("#[0-9]+")) throw new IllegalArgumentException("Give the finish mob a name such as Finish.");
        if (!Double.isFinite(returnHeight) || returnHeight < 1 || returnHeight > 20) throw new IllegalArgumentException("Return height must be between 1 and 20 blocks.");
    }
    public static RaceCourse defaults(String mapId) { return new RaceCourse(mapId, 15, "Finish", DEFAULT_RETURN_HEIGHT); }
    public int checkpoint(String name) {
        if (name == null) return -1;
        if (name.equals(finishName)) return Integer.MAX_VALUE;
        if (!name.matches("#[1-9][0-9]{0,4}")) return -1;
        return Integer.parseInt(name.substring(1));
    }
}
