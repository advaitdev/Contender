package me.advait.contender.tournament;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinScheduleTest {
    @Test void firstRoundFollowsTheEntryList() {
        List<RoundRobinSchedule.Pairing> six = RoundRobinSchedule.create(6);
        assertPair(six.get(0), 0, 1);
        assertPair(six.get(1), 2, 3);
        assertPair(six.get(2), 4, 5);
        // With an odd count the last entry sits out the first round.
        List<RoundRobinSchedule.Pairing> five = RoundRobinSchedule.create(5).stream().filter(p -> p.round() == 1).toList();
        assertEquals(2, five.size());
        assertPair(five.get(0), 0, 1);
        assertPair(five.get(1), 2, 3);
    }

    @Test void everyoneMeetsOnceAndNobodyIsDoubleBooked() {
        for (int entries = 2; entries <= 16; entries++) {
            List<RoundRobinSchedule.Pairing> matches = RoundRobinSchedule.create(entries);
            assertEquals(entries * (entries - 1) / 2, matches.size(), entries + " entries");
            Set<String> pairs = new HashSet<>();
            for (RoundRobinSchedule.Pairing match : matches) {
                assertNotEquals(match.first(), match.second());
                assertTrue(pairs.add(Math.min(match.first(), match.second()) + "-" + Math.max(match.first(), match.second())), "repeat in " + entries);
            }
            for (int round = 1; round <= RoundRobinSchedule.fullRounds(entries); round++) {
                Set<Integer> busy = new HashSet<>();
                for (RoundRobinSchedule.Pairing match : matches) {
                    if (match.round() != round) continue;
                    assertTrue(busy.add(match.first()) && busy.add(match.second()), "double booked in round " + round);
                }
            }
        }
    }

    @Test void savedTournamentsKeepTheOldLayout() {
        List<RoundRobinSchedule.Pairing> legacy = RoundRobinSchedule.create(4, 3, 1);
        assertPair(legacy.get(0), 0, 3);
        assertPair(legacy.get(1), 1, 2);
    }

    private static void assertPair(RoundRobinSchedule.Pairing pairing, int a, int b) {
        assertEquals(Set.of(a, b), Set.of(pairing.first(), pairing.second()), pairing.toString());
    }
}
