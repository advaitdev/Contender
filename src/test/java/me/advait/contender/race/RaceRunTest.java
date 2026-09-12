package me.advait.contender.race;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceRunTest {
    private RaceRun run(int advance, int racers) {
        List<RaceRun.Racer> roster = new ArrayList<>();
        for (int i = 0; i < racers; i++) roster.add(new RaceRun.Racer(UUID.randomUUID(), "Racer" + i));
        return new RaceRun(UUID.randomUUID(), "Mace Race", "course", advance, 0, roster);
    }
    @Test void allowsFifteenAheadAndRejectsSixteenWithoutChangingCheckpoint() {
        RaceRun run = run(15, 2); UUID id = run.racers().getFirst().id();
        run.countdown(30); run.start(10);
        assertEquals(RaceRun.Hit.CHECKPOINT, run.hit(id, 1, 100));
        assertEquals(RaceRun.Hit.TOO_FAR, run.hit(id, 17, 200)); assertEquals(1, run.racer(id).checkpoint());
        assertEquals(RaceRun.Hit.CHECKPOINT, run.hit(id, 16, 200));
        assertEquals(RaceRun.Hit.IGNORED, run.hit(id, 1, 201)); assertEquals(16, run.racer(id).checkpoint());
        assertEquals(RaceRun.Hit.FINISH, run.hit(id, 31, 500)); assertEquals(490, run.racer(id).finishNanos());
        assertEquals(RaceRun.Hit.IGNORED, run.hit(id, 31, 1000)); assertEquals(490, run.racer(id).finishNanos());
    }
    @Test void countdownAndUnknownPlayersCannotAdvanceAndFinishMustBeInRange() {
        var run = run(2, 1); UUID id = run.racers().getFirst().id();
        run.countdown(5); assertEquals(RaceRun.Hit.IGNORED, run.hit(id, 1, 50));
        run.start(100);
        assertEquals(RaceRun.Hit.IGNORED, run.hit(UUID.randomUUID(), 1, 110));
        assertEquals(RaceRun.Hit.TOO_FAR, run.hit(id, 6, 120));
        assertEquals(RaceRun.Hit.CHECKPOINT, run.hit(id, 2, 150));
        assertEquals(RaceRun.Hit.CHECKPOINT, run.hit(id, 4, 160));
        assertEquals(RaceRun.Hit.FINISH, run.hit(id, 6, 170));
        assertTrue(run.allDone());
    }
    @Test void oneClockContinuesForEveryRacerAndSameTickFinishesKeepHitOrder() {
        var run = run(15, 3); var a = run.racers().get(0); var b = run.racers().get(1); var c = run.racers().get(2);
        run.countdown(1); run.start(1_000_000);
        run.hit(b.id(), 2, 6_000_000); run.hit(a.id(), 2, 6_000_000);
        assertEquals(1, b.place()); assertEquals(2, a.place()); assertEquals(List.of(b, a, c), run.standings());
        assertEquals(5_000_000, run.elapsed(6_000_000));
        run.withdraw(c.id()); assertTrue(run.allDone()); assertEquals(RaceRun.Hit.IGNORED, run.hit(c.id(), 2, 7_000_000));
        run.end(RaceRun.State.FINISHED); assertEquals(5_000_000, a.finishNanos());
        assertEquals("1:02.034", RaceRun.time(62_034_000_000L));
    }
    @Test void unfinishedLeaderboardUsesProgressAndEndingPreservesResults() {
        var run = run(15, 3); var a = run.racers().get(0); var b = run.racers().get(1); var c = run.racers().get(2);
        run.countdown(20); run.start(0); run.hit(a.id(), 1, 10); run.hit(b.id(), 8, 20);
        assertEquals(List.of(b, a, c), run.standings());
        run.hit(b.id(), 21, 100); run.end(RaceRun.State.FINISHED);
        assertFalse(b.withdrawn()); assertTrue(a.withdrawn()); assertEquals(b, run.standings().getFirst());
        assertEquals(100, b.finishNanos());
    }
    @Test void validatesRosterAndCourseSettings() {
        var a = new RaceRun.Racer(UUID.randomUUID(), "Alice");
        assertThrows(IllegalArgumentException.class, () -> new RaceRun(UUID.randomUUID(), "Race", "m", 15, 0, List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> run(0, 2));
        assertThrows(IllegalArgumentException.class, () -> run(15, 65));
        assertThrows(IllegalArgumentException.class, () -> new RaceCourse("m", 15, "#1", 3));
        var course = RaceCourse.defaults("m"); assertEquals(15, course.maxAdvance());
        assertTrue(course.returnOnGround());
        assertTrue(new RaceCourse("m", 10, "Finish", 4).returnOnGround());
        assertEquals(1, course.checkpoint("#1")); assertEquals(-1, course.checkpoint("#0"));
        assertEquals(-1, course.checkpoint("#100000000000000000000")); assertEquals(Integer.MAX_VALUE, course.checkpoint("Finish"));
    }
}
