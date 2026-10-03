package me.advait.contender.nametag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthTagsTest {
    @Test void wholeNumbersFromThreeUp() {
        assertEquals("20", HealthTags.format(20, 20));
        assertEquals("17", HealthTags.format(16.6, 20));
        assertEquals("3", HealthTags.format(3, 20));
    }

    @Test void oneDecimalBelowThree() {
        assertEquals("2.5", HealthTags.format(2.5, 20));
        assertEquals("0.4", HealthTags.format(0.4, 20));
        assertEquals("0.0", HealthTags.format(0, 20));
    }

    @Test void scaledToTwenty() {
        // A Juggernaut or a hacker with extra hearts still reads out of 20.
        assertEquals("20", HealthTags.format(40, 40));
        assertEquals("10", HealthTags.format(20, 40));
        assertEquals("1.5", HealthTags.format(3, 40));
    }
}
