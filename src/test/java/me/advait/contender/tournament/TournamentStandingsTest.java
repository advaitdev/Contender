package me.advait.contender.tournament;

import me.advait.contender.duel.DuelResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TournamentStandingsTest {
    private static Tournament tournament(int players) {
        List<TournamentEntry> entries = new ArrayList<>();
        for (int i = 0; i < players; i++) entries.add(new TournamentEntry("P" + i, List.of(UUID.randomUUID())));
        return new Tournament(UUID.randomUUID(), "Test", "map", "kit", entries, false, false, 3, 10, 4);
    }

    @Test void everyPairMeetsOnceAndNobodyPlaysTwiceInARound() {
        for (int players = 2; players <= 9; players++) {
            Tournament tournament = tournament(players);
            Set<String> pairs = new HashSet<>();
            Map<Integer, Set<Integer>> busy = new HashMap<>();
            for (TournamentMatch match : tournament.matches()) {
                int a = Math.min(match.first(), match.second()), b = Math.max(match.first(), match.second());
                assertNotEquals(a, b);
                assertTrue(pairs.add(a + "-" + b), "pair repeated for " + players + " players");
                Set<Integer> round = busy.computeIfAbsent(match.round(), r -> new HashSet<>());
                assertTrue(round.add(a) && round.add(b), "someone plays twice in round " + match.round());
            }
            assertEquals(players * (players - 1) / 2, pairs.size());
        }
    }

    @Test void onePointPerMatchWon() {
        Tournament tournament = tournament(3);
        for (TournamentMatch match : tournament.matches()) {
            match.start();
            // The lower entry index always wins 2-1.
            boolean firstLower = match.first() < match.second();
            match.finish(new DuelResult(DuelResult.Reason.FINISHED, firstLower ? 2 : 1, firstLower ? 1 : 2, firstLower ? 1 : 2));
        }
        var standings = tournament.standings();
        assertEquals(List.of("P0", "P1", "P2"), standings.stream().map(s -> s.entry().name()).toList());
        assertEquals(List.of(2, 1, 0), standings.stream().map(Tournament.Standing::points).toList());
        for (var standing : standings) assertEquals(standing.wins(), standing.points());
    }

    @Test void drawsAndUnplayedMatchesScoreNothing() {
        Tournament tournament = tournament(2);
        TournamentMatch match = tournament.matches().getFirst();
        match.start();
        match.finish(new DuelResult(DuelResult.Reason.FINISHED, 1, 1, null));
        for (var standing : tournament.standings()) {
            assertEquals(0, standing.points());
            assertEquals(1, standing.draws());
        }
    }

    @Test void roundDifferenceBreaksTies() {
        Tournament tournament = tournament(4);
        // P0 beats P1 3-0 and P2 beats P3 2-1: both have one win, P0 has the better difference.
        for (TournamentMatch match : tournament.matches()) {
            int a = Math.min(match.first(), match.second()), b = Math.max(match.first(), match.second());
            if (a == 0 && b == 1 || a == 2 && b == 3) {
                match.start();
                boolean firstIsA = match.first() == a;
                int winnerScore = a == 0 ? 3 : 2, loserScore = a == 0 ? 0 : 1;
                match.finish(new DuelResult(DuelResult.Reason.FINISHED, firstIsA ? winnerScore : loserScore, firstIsA ? loserScore : winnerScore, firstIsA ? 1 : 2));
            }
        }
        var standings = tournament.standings();
        assertEquals("P0", standings.get(0).entry().name());
        assertEquals("P2", standings.get(1).entry().name());
    }

    @Test void rejectsDuplicatePlayers() {
        UUID same = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new Tournament(UUID.randomUUID(), "Test", "map", "kit",
                List.of(new TournamentEntry("A", List.of(same)), new TournamentEntry("B", List.of(same))), false, false, 1, 10, 4));
    }

    @Test void eachRoundHasItsOwnStatus() {
        Tournament tournament = tournament(4);
        tournament.resume();
        assertEquals("Not started", tournament.roundStatus(1));
        List<TournamentMatch> first = tournament.matches().stream().filter(m -> m.round() == 1).toList();
        first.getFirst().start();
        assertEquals("Playing", tournament.roundStatus(1));
        for (TournamentMatch match : first) {
            if (match.status() != TournamentMatch.Status.PLAYING) match.start();
            match.finish(new me.advait.contender.duel.DuelResult(me.advait.contender.duel.DuelResult.Reason.FINISHED, 2, 0, 1));
        }
        assertTrue(tournament.roundFinished(1));
        assertEquals("Finished", tournament.roundStatus(1));
        assertEquals("Not started", tournament.roundStatus(2));
        tournament.pause();
        assertEquals("Paused", tournament.roundStatus(2));
    }
}
