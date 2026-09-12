package me.advait.contender.combo;

/** Sword attacks never run faster than a fully charged vanilla sword swing (13 ticks). */
public enum ComboDifficulty {
    EASY("Easy", 2.2, 0.07f, 24, 10, 5, 8, 0.2f),
    NORMAL("Normal", 2.5, 0.09f, 20, 8, 4, 12, 0.35f),
    HARD("Hard", 2.75, 0.10f, 16, 6, 3, 18, 0.5f);
    private final String label;
    private final double reach;
    private final float speed, turnDegrees, airControl;
    private final int attackTicks, reactionTicks, aimTicks;
    ComboDifficulty(String label, double reach, float speed, int attackTicks, int reactionTicks, int aimTicks, float turnDegrees, float airControl) {
        this.label = label; this.reach = reach; this.speed = speed;
        this.attackTicks = attackTicks; this.reactionTicks = reactionTicks;
        this.aimTicks = aimTicks; this.turnDegrees = turnDegrees; this.airControl = airControl;
    }
    public String label() { return label; }
    public double reach() { return reach; }
    public float speed() { return speed; }
    public int attackTicks() { return attackTicks; }
    public int reactionTicks() { return reactionTicks; }
    public int aimTicks() { return aimTicks; }
    public float turnDegrees() { return turnDegrees; }
    public float airControl() { return airControl; }
}
