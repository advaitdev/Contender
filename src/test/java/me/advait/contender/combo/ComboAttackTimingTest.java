package me.advait.contender.combo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComboAttackTimingTest {
    @Test void noAttacksBeforeThePlayersFirstHit() {
        var timing = new ComboAttackTiming(ComboDifficulty.NORMAL);
        for (int tick = 0; tick < 200; tick++) {
            assertFalse(timing.reacting(tick));
            assertFalse(timing.shouldSwing(tick, true));
        }
    }

    @Test void recoveryAndAimingMustBothFinishBeforeTheFirstReturnSwing() {
        var timing = new ComboAttackTiming(ComboDifficulty.NORMAL);
        timing.hit(10);
        for (int tick = 10; tick < 18; tick++) assertFalse(timing.shouldSwing(tick, true));
        assertTrue(timing.shouldSwing(18, true));
        for (int tick = 19; tick < 38; tick++) assertFalse(timing.shouldSwing(tick, true));
        assertTrue(timing.shouldSwing(38, true));
    }

    @Test void reenteringReachDoesNotCauseAnInstantReturnHit() {
        var timing = new ComboAttackTiming(ComboDifficulty.NORMAL);
        timing.hit(0);
        for (int tick = 8; tick < 12; tick++) assertFalse(timing.shouldSwing(tick, true));
        assertFalse(timing.shouldSwing(12, false));
        for (int tick = 13; tick < 17; tick++) assertFalse(timing.shouldSwing(tick, true));
        assertTrue(timing.shouldSwing(17, true));
    }

    @Test void anotherAcceptedHitRestartsRecoveryAndTargetAcquisition() {
        var timing = new ComboAttackTiming(ComboDifficulty.NORMAL);
        timing.hit(0);
        for (int tick = 8; tick < 12; tick++) assertFalse(timing.shouldSwing(tick, true));
        timing.hit(12);
        for (int tick = 12; tick < 20; tick++) assertFalse(timing.shouldSwing(tick, true));
        assertTrue(timing.shouldSwing(20, true));
    }

    @Test void normalCanHitBackBetweenRepeatedWeakHitsAtCloseRange() {
        var timing = new ComboAttackTiming(ComboDifficulty.NORMAL);
        int swings = 0;
        for (int tick = 0; tick < 100; tick++) {
            if (tick % 10 == 0) timing.hit(tick);
            if (timing.shouldSwing(tick, true)) swings++;
        }
        assertEquals(5, swings);
    }

    @Test void retryClearsThePreviousTurnsAimAndCooldown() {
        var timing = new ComboAttackTiming(ComboDifficulty.NORMAL);
        timing.hit(0);
        assertFalse(timing.shouldSwing(8, true)); assertTrue(timing.shouldSwing(12, true));
        timing.reset();
        assertFalse(timing.shouldSwing(100, true));
        timing.hit(101);
        assertFalse(timing.shouldSwing(109, true)); assertTrue(timing.shouldSwing(113, true));
    }
}
