package me.advait.contender.combo;

/** Recovery, time to acquire a target, and sword cooldown all have to finish before a swing. */
final class ComboAttackTiming {
    private final ComboDifficulty difficulty;
    private int reactAt, nextAttack, targetSince;

    ComboAttackTiming(ComboDifficulty difficulty) {
        this.difficulty = difficulty;
        reset();
    }

    void reset() {
        reactAt = Integer.MAX_VALUE;
        nextAttack = 0;
        targetSince = -1;
    }

    void hit(int tick) {
        reactAt = tick + difficulty.reactionTicks();
        targetSince = -1;
    }

    boolean reacting(int tick) { return tick >= reactAt; }

    boolean shouldSwing(int tick, boolean aimedInReach) {
        if (!aimedInReach) {
            targetSince = -1;
            return false;
        }
        if (targetSince < 0) targetSince = tick;
        // Aiming can settle during recovery; adding the delays would let weak sword spam
        // restart them forever, even when the player stays directly in front of the bot.
        if (!reacting(tick) || tick - targetSince < difficulty.aimTicks() || tick < nextAttack) return false;
        nextAttack = tick + difficulty.attackTicks();
        return true;
    }
}
