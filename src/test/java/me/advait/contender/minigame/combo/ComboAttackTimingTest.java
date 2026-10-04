package me.advait.contender.minigame.combo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComboAttackTimingTest {
    @Test void waitsForReactionAimAndCooldown() {
        ComboDifficulty difficulty = ComboDifficulty.NORMAL;
        ComboAttackTiming timing = new ComboAttackTiming(difficulty);
        assertFalse(timing.shouldSwing(0, true), "no swing before the first hit");
        timing.hit(10);
        int react = 10 + difficulty.reactionTicks();
        assertFalse(timing.reacting(react - 1));
        assertTrue(timing.reacting(react));
        // Aim starts counting while recovering, so the first swing comes once both have passed.
        for (int tick = 11; tick < react; tick++) assertFalse(timing.shouldSwing(tick, true));
        assertTrue(timing.shouldSwing(react, true));
        assertFalse(timing.shouldSwing(react + 1, true), "sword cooldown");
        assertTrue(timing.shouldSwing(react + difficulty.attackTicks(), true));
    }

    @Test void neverSwingsFasterThanAChargedSword() {
        for (ComboDifficulty difficulty : ComboDifficulty.values()) assertTrue(difficulty.attackTicks() >= 13, difficulty.name());
    }
}
