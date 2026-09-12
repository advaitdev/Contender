package me.advait.contender.combo;

/** Sword attacks never run faster than a fully charged vanilla sword swing (13 ticks). */
public enum ComboDifficulty {
    EASY("Easy", 2.5, 0.08f, 20, 8),
    NORMAL("Normal", 2.8, 0.10f, 15, 5),
    HARD("Hard", 3.0, 0.13f, 13, 2);
    private final String label;
    private final double reach;
    private final float speed;
    private final int attackTicks, reactionTicks;
    ComboDifficulty(String label, double reach, float speed, int attackTicks, int reactionTicks) {
        this.label = label; this.reach = reach; this.speed = speed;
        this.attackTicks = attackTicks; this.reactionTicks = reactionTicks;
    }
    public String label() { return label; }
    public double reach() { return reach; }
    public float speed() { return speed; }
    public int attackTicks() { return attackTicks; }
    public int reactionTicks() { return reactionTicks; }
}
