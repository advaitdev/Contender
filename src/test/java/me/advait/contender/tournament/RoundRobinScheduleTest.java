package me.advait.contender.tournament;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundRobinScheduleTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 8, 9, 20, 63, 64})
    void everyPairMeetsExactlyOnceWithoutDoubleBookingARound(int count) {
        var schedule = RoundRobinSchedule.create(count);
        assertEquals(count * (count - 1) / 2, schedule.size());
        Set<Set<Integer>> pairs = new HashSet<>();
        Map<Integer, Set<Integer>> rounds = new HashMap<>();
        for (var match : schedule) {
            assertNotEquals(match.first(), match.second());
            assertTrue(match.first() >= 0 && match.first() < count);
            assertTrue(match.second() >= 0 && match.second() < count);
            assertTrue(pairs.add(Set.of(match.first(), match.second())));
            Set<Integer> playing = rounds.computeIfAbsent(match.round(), ignored -> new HashSet<>());
            assertTrue(playing.add(match.first()));
            assertTrue(playing.add(match.second()));
        }
        assertEquals(count % 2 == 0 ? count - 1 : count, rounds.size());
    }
}
