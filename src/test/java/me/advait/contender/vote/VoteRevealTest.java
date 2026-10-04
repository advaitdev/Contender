package me.advait.contender.vote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoteRevealTest {
    @Test void sweepEndsOnTheTargetAfterTurningAroundTwice() {
        for (int count = 2; count <= 12; count++) {
            for (int target = 0; target < count; target++) {
                List<Integer> steps = VoteReveal.sweep(count, target);
                assertEquals(0, steps.getFirst(), "starts at one end");
                assertEquals(target, steps.getLast(), "stops on the voted-out player");
                int turns = 0;
                for (int i = 1; i < steps.size(); i++) {
                    assertEquals(1, Math.abs(steps.get(i) - steps.get(i - 1)), "moves one spot at a time");
                    assertTrue(steps.get(i) >= 0 && steps.get(i) < count, "stays on the line");
                    if (i >= 2 && steps.get(i) - steps.get(i - 1) != steps.get(i - 1) - steps.get(i - 2)) turns++;
                }
                assertTrue(turns >= 2, "sweeps back and forth at least once (" + count + " players, target " + target + ")");
            }
        }
    }
}
