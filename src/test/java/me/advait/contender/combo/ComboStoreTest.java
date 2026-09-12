package me.advait.contender.combo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ComboStoreTest {
    @TempDir Path folder;
    @Test void restartPreservesInfinityAndNumericResultsButInterruptsUnfinishedPlayers() {
        var store = new ComboStore(folder.toFile()); var run = ComboRunTest.run(3, 5);
        var first = run.entries().get(0); var second = run.entries().get(1); var last = run.entries().get(2);
        run.start(); run.begin(first.id()); run.hit(first.id()); run.clear();
        run.begin(second.id()); for (int i = 0; i < 7; i++) run.hit(second.id()); run.hitBack();
        run.begin(last.id()); run.hit(last.id()); store.save(run);
        var restored = store.load();
        assertEquals(ComboRun.State.INTERRUPTED, restored.state()); assertNull(restored.playing());
        assertTrue(restored.entry(first.id()).cleared()); assertEquals("∞", restored.entry(first.id()).value());
        assertEquals(7, restored.entry(second.id()).score()); assertTrue(restored.entry(last.id()).withdrawn());
        assertEquals("sword", restored.kitId()); assertEquals(ComboDifficulty.NORMAL, restored.difficulty());
    }
    @Test void readyOfflineRosterRemainsReadyAcrossRestart() {
        var store = new ComboStore(folder.toFile()); var run = ComboRunTest.run(2, 0); store.save(run);
        var restored = store.load(); assertEquals(ComboRun.State.READY, restored.state());
        assertEquals(run.entries().getFirst().id(), restored.entries().getFirst().id()); assertFalse(restored.entries().getFirst().done());
    }
}
