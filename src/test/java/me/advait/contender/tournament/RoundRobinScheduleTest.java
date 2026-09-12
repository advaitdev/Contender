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

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 10, 63, 64})
    void shortenedSchedulesKeepWholeRoundsAndNeverRepeatOpponents(int count) {
        int full = RoundRobinSchedule.fullRounds(count);
        for (int limit : new HashSet<>(List.of(1, Math.min(3, full), full))) {
            var schedule = RoundRobinSchedule.create(count, limit);
            assertEquals(count / 2 * limit, schedule.size());
            assertEquals(RoundRobinSchedule.create(count).stream().filter(pair -> pair.round() <= limit).toList(), schedule);
            Set<Set<Integer>> opponents = new HashSet<>();
            Map<Integer, Set<Integer>> rounds = new HashMap<>();
            int[] games = new int[count];
            for (var pair : schedule) {
                assertTrue(opponents.add(Set.of(pair.first(), pair.second())));
                var round = rounds.computeIfAbsent(pair.round(), ignored -> new HashSet<>());
                assertTrue(round.add(pair.first())); assertTrue(round.add(pair.second()));
                games[pair.first()]++; games[pair.second()]++;
            }
            assertEquals(limit, rounds.size());
            for (int played : games) {
                if (count % 2 == 0) assertEquals(limit, played);
                else assertTrue(played == limit || played == limit - 1, "At most one bye per entry");
            }
        }
        for (int invalid : List.of(-1, 0, full + 1)) {
            assertThrows(IllegalArgumentException.class, () -> RoundRobinSchedule.create(count, invalid));
        }
    }
}
