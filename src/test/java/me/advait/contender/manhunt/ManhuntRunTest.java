package me.advait.contender.manhunt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManhuntRunTest {
    @TempDir Path directory;
    private ManhuntRun.Entry entry(String name, ManhuntRun.Team team) { return new ManhuntRun.Entry(UUID.randomUUID(), name, team); }
    private ManhuntRun run(List<ManhuntRun.Entry> players) { return new ManhuntRun(UUID.randomUUID(), "Manhunt", "axe", "end", players, 10); }
    @Test void randomTeamsAreBalancedAndContainEveryPlayerExactlyOnce() {
        for (int count = 2; count <= 64; count++) {
            List<ManhuntRun.Entry> players = new ArrayList<>(); for (int i = 0; i < count; i++) players.add(entry("Player" + i, ManhuntRun.Team.HUNTER));
            var assigned = ManhuntRun.randomTeams(players, new Random(12)); var game = run(assigned);
            assertEquals(count / 2, game.alive(ManhuntRun.Team.RUNNER));
            assertEquals(count - count / 2, game.alive(ManhuntRun.Team.HUNTER));
            assertEquals(new HashSet<>(players.stream().map(ManhuntRun.Entry::id).toList()), new HashSet<>(assigned.stream().map(ManhuntRun.Entry::id).toList()));
        }
    }
    @Test void lastRunnerDeathEndsGameAndCannotOverwriteResult() {
        var a = entry("Alice", ManhuntRun.Team.RUNNER); var b = entry("Bob", ManhuntRun.Team.RUNNER); var c = entry("Carol", ManhuntRun.Team.HUNTER);
        var game = run(List.of(a, b, c)); game.countdown(); game.start();
        assertTrue(game.eliminate(a.id())); assertFalse(game.eliminate(a.id())); assertFalse(game.terminal());
        assertTrue(game.eliminate(b.id())); assertEquals(ManhuntRun.State.HUNTERS_WON, game.state());
        assertFalse(game.dragonDied()); game.end(ManhuntRun.State.CANCELLED); assertEquals(ManhuntRun.State.HUNTERS_WON, game.state());
    }
    @Test void huntersHaveOneLifeAndRunnersStillNeedToKillDragon() {
        var runner = entry("Alice", ManhuntRun.Team.RUNNER); var hunter = entry("Bob", ManhuntRun.Team.HUNTER);
        var game = run(List.of(runner, hunter)); game.countdown(); game.start();
        assertTrue(game.eliminate(hunter.id())); assertFalse(game.alive(hunter.id())); assertEquals(0, game.alive(ManhuntRun.Team.HUNTER));
        assertFalse(game.terminal()); assertTrue(game.dragonDied()); assertEquals(ManhuntRun.State.RUNNERS_WON, game.state());
        assertFalse(game.eliminate(runner.id())); assertTrue(game.alive(runner.id()));
    }
    @Test void dragonDeathIsIgnoredBeforeStartAndAnyLiveDeathWinsWithoutKillerAttribution() {
        var game = run(List.of(entry("Alice", ManhuntRun.Team.RUNNER), entry("Bob", ManhuntRun.Team.HUNTER)));
        assertFalse(game.dragonDied()); game.countdown(); assertFalse(game.dragonDied()); game.start();
        assertTrue(game.dragonDied()); assertEquals(ManhuntRun.State.RUNNERS_WON, game.state()); assertFalse(game.dragonDied());
    }
    @Test void rostersRejectDuplicatesMissingTeamsAndBadCountdowns() {
        var runner = entry("Alice", ManhuntRun.Team.RUNNER);
        assertThrows(IllegalArgumentException.class, () -> run(List.of(runner, new ManhuntRun.Entry(runner.id(), "Alice", ManhuntRun.Team.HUNTER))));
        assertThrows(IllegalArgumentException.class, () -> run(List.of(runner, entry("Bob", ManhuntRun.Team.RUNNER))));
        assertThrows(IllegalArgumentException.class, () -> new ManhuntRun(UUID.randomUUID(), "Manhunt", "axe", "end", List.of(runner, entry("Bob", ManhuntRun.Team.HUNTER)), 0));
    }
    @Test void persistenceKeepsTeamsAndResultsAndInterruptsLiveGamesAfterRestart() {
        var runner = entry("Alice", ManhuntRun.Team.RUNNER); var hunter = entry("Bob", ManhuntRun.Team.HUNTER);
        var game = run(List.of(runner, hunter)); var store = new ManhuntStore(directory.toFile());
        store.save(game); var ready = store.load(); assertEquals(ManhuntRun.State.READY, ready.state()); assertEquals(game.entries(), ready.entries());
        game.countdown(); game.start(); game.eliminate(hunter.id()); store.save(game);
        var interrupted = store.load(); assertEquals(ManhuntRun.State.INTERRUPTED, interrupted.state()); assertFalse(interrupted.alive(hunter.id())); assertEquals(game.id(), interrupted.id());
        game.dragonDied(); store.save(game); assertEquals(ManhuntRun.State.RUNNERS_WON, store.load().state());
    }
}
