package me.advait.contender.combo;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComboRunTest {
    static ComboRun run(int players, int grace) {
        List<ComboRun.Entry> entries = new ArrayList<>();
        for (int i = 0; i < players; i++) entries.add(new ComboRun.Entry(UUID.randomUUID(), "Player" + i));
        return new ComboRun(UUID.randomUUID(), "Combo", "platform", "sword", ComboDifficulty.NORMAL, grace, entries);
    }
    @Test void botWaitsForFirstHitAndGraceIncludesFiveButNotSix() {
        var run = run(1, 5); UUID id = run.entries().getFirst().id(); run.start(); run.begin(id);
        assertEquals(ComboRun.Result.IGNORED, run.hitBack()); assertFalse(run.clear());
        for (int i = 0; i < 5; i++) assertTrue(run.hit(id));
        assertEquals(ComboRun.Result.RETRY, run.hitBack()); assertEquals(0, run.hits()); assertFalse(run.entry(id).done());
        for (int i = 0; i < 6; i++) run.hit(id);
        assertEquals(ComboRun.Result.SCORED, run.hitBack()); assertEquals(6, run.entry(id).score()); assertNull(run.playing());
        assertFalse(run.hit(id)); assertEquals(ComboRun.Result.IGNORED, run.hitBack());
    }
    @Test void infinityOutranksNumericScoresAndSurvivesFinishing() {
        var run = run(3, 0); var a = run.entries().get(0); var b = run.entries().get(1); var c = run.entries().get(2);
        run.start(); run.begin(a.id()); for (int i = 0; i < 30; i++) run.hit(a.id()); run.hitBack();
        run.begin(b.id()); run.hit(b.id()); assertTrue(run.clear());
        run.begin(c.id()); for (int i = 0; i < 10; i++) run.hit(c.id()); run.hitBack();
        run.end(ComboRun.State.FINISHED);
        assertEquals(List.of(b, a, c), run.standings()); assertEquals("∞", b.value()); assertTrue(run.allDone());
    }
    @Test void disconnectAfterGraceCannotBuyAnotherAttempt() {
        var run = run(2, 5); var a = run.entries().get(0); var b = run.entries().get(1); run.start();
        run.begin(a.id()); for (int i = 0; i < 5; i++) run.hit(a.id()); run.disconnect(a.id());
        assertFalse(a.done()); assertNull(run.playing());
        run.begin(b.id()); for (int i = 0; i < 6; i++) run.hit(b.id()); run.disconnect(b.id());
        assertEquals(6, b.score()); assertTrue(b.done());
        run.begin(a.id()); assertEquals(0, run.hits()); assertFalse(run.hit(b.id()));
    }
    @Test void retryAndWithdrawalDoNotCarryAnAttemptIntoTheNextPlayer() {
        var run = run(2, 5); var a = run.entries().get(0); var b = run.entries().get(1); run.start(); run.begin(a.id());
        for (int i = 0; i < 8; i++) run.hit(a.id()); run.retry(); assertEquals(0, run.hits());
        run.hit(a.id()); run.withdraw(a.id()); assertTrue(a.withdrawn()); assertNull(a.score());
        run.begin(b.id()); assertEquals(0, run.hits()); run.end(ComboRun.State.CANCELLED); assertTrue(b.withdrawn());
    }
    @Test void validatesRosterAndKeepsNormalSwordCadenceAtEveryDifficulty() {
        var entry = new ComboRun.Entry(UUID.randomUUID(), "Alice");
        assertThrows(IllegalArgumentException.class, () -> new ComboRun(UUID.randomUUID(), "Combo", "map", "kit", ComboDifficulty.NORMAL, 5, List.of(entry, entry)));
        assertThrows(IllegalArgumentException.class, () -> run(65, 5));
        assertThrows(IllegalArgumentException.class, () -> run(0, 5));
        assertThrows(IllegalArgumentException.class, () -> run(1, -1));
        for (var difficulty : ComboDifficulty.values()) assertTrue(difficulty.attackTicks() >= 13);
    }
}
