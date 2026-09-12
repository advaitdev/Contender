package me.advait.contender.tournament;

import me.advait.contender.duel.DuelResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TournamentTest {
    @TempDir Path directory;
    private List<TournamentEntry> entries(int count) {
        List<TournamentEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(new TournamentEntry("Player" + i, List.of(UUID.randomUUID())));
        return entries;
    }
    private Tournament tournament(boolean roundBarrier, int parallel) {
        return new Tournament(UUID.randomUUID(), "Friday", "forest", "sword", entries(6), false, roundBarrier, 3, 10, parallel);
    }
    private DuelResult win(int side) { return new DuelResult(DuelResult.Reason.FINISHED, side == 1 ? 2 : 0, side == 2 ? 2 : 0, side); }

    @Test void availablePlayersCanAdvanceWhileAnotherMatchIsStillPlaying() {
        for (boolean barrier : List.of(false, true)) {
            Tournament tournament = tournament(barrier, 20);
            tournament.resume();
            for (int i = 0; i < 3; i++) tournament.nextMatch(uuid -> true).start();
            tournament.matches().get(1).finish(win(1));
            tournament.matches().get(2).finish(win(2));
            var next = tournament.nextMatch(uuid -> true);
            if (barrier) assertNull(next);
            else { assertNotNull(next); assertEquals(2, next.round()); }
        }
    }
    @Test void skipsOfflineRostersAndRespectsTheParallelLimit() {
        Tournament tournament = tournament(false, 1);
        UUID offline = tournament.entries().getFirst().players().getFirst();
        tournament.resume();
        var match = tournament.nextMatch(uuid -> !uuid.equals(offline));
        assertNotNull(match);
        assertNotEquals(0, match.first()); assertNotEquals(0, match.second());
        match.start();
        assertNull(tournament.nextMatch(uuid -> true));
        match.finish(win(1));
        assertNotNull(tournament.nextMatch(uuid -> true));
        tournament.pause();
        assertNull(tournament.nextMatch(uuid -> true));
    }
    @Test void standingsCountDrawsForfeitsAndRoundDifference() {
        Tournament tournament = new Tournament(UUID.randomUUID(), "Cup", "map", "kit", entries(3), false, false, 3, 10, 20);
        tournament.matches().get(0).finish(new DuelResult(DuelResult.Reason.FORFEIT, 0, 0, 1));
        tournament.matches().get(1).finish(new DuelResult(DuelResult.Reason.FINISHED, 1, 1, null));
        tournament.matches().get(2).finish(new DuelResult(DuelResult.Reason.FINISHED, 2, 0, 1));
        assertTrue(tournament.isComplete());
        assertEquals(8, tournament.standings().stream().mapToInt(Tournament.Standing::points).sum());
        assertEquals(0, tournament.standings().stream().mapToInt(Tournament.Standing::roundDifference).sum());
        assertEquals(6, tournament.standings().stream().mapToInt(Tournament.Standing::played).sum());
        assertThrows(IllegalStateException.class, tournament::resume);
    }
    @Test void cancelledDuelsCanRetryAndStartedMatchesCannotChangeLength() {
        Tournament tournament = tournament(false, 20);
        TournamentMatch match = tournament.matches().getFirst();
        match.setBestOf(7); match.start();
        assertThrows(IllegalStateException.class, () -> match.setBestOf(5));
        match.finish(new DuelResult(DuelResult.Reason.CANCELLED, 2, 1, 1));
        assertEquals(TournamentMatch.Status.WAITING, match.status());
        assertNull(match.result());
        assertEquals(0, tournament.standings().stream().mapToInt(Tournament.Standing::points).sum());
        assertThrows(IllegalArgumentException.class, () -> match.setBestOf(4));
    }
    @Test void restartPreservesScoresAndOverridesAndPausesUnfinishedMatches() {
        Tournament tournament = tournament(true, 4);
        tournament.matches().getFirst().setBestOf(7);
        tournament.matches().getFirst().finish(win(1));
        tournament.matches().get(1).start();
        tournament.resume();
        TournamentStore store = new TournamentStore(directory.resolve("tournament.yml").toFile());
        store.save(tournament);
        Tournament loaded = store.load();
        assertEquals(tournament.id(), loaded.id());
        assertEquals(tournament.entries(), loaded.entries());
        assertEquals(tournament.standings(), loaded.standings());
        assertEquals(7, loaded.matches().getFirst().bestOf());
        assertEquals(TournamentMatch.Status.WAITING, loaded.matches().get(1).status());
        assertFalse(loaded.isRunning());
        assertTrue(loaded.waitForRound());
        assertEquals(4, loaded.maxParallel());
    }
    @Test void playersCannotEnterOnMultipleTeams() {
        UUID player = UUID.randomUUID();
        List<TournamentEntry> entries = List.of(new TournamentEntry("Red", List.of(player)), new TournamentEntry("Blue", List.of(player)));
        assertThrows(IllegalArgumentException.class, () -> new Tournament(UUID.randomUUID(), "Cup", "map", "kit", entries, true, false, 3, 10, 20));
    }
    @Test void aShortenedStageCompletesAfterTheChosenRoundsWithoutRemovingEntries() {
        Tournament tournament = new Tournament(UUID.randomUUID(), "Axe", "map", "kit", entries(10), false, true, 3, 10, 20, 3);
        tournament.resume();
        int played = 0;
        TournamentMatch match;
        while ((match = tournament.nextMatch(uuid -> true)) != null) {
            assertTrue(match.round() <= 3);
            match.start(); match.finish(win(1)); played++;
        }
        assertEquals(15, played);
        assertEquals(3, tournament.rounds());
        assertTrue(tournament.isComplete());
        assertEquals(10, tournament.entries().size());
        assertTrue(tournament.standings().stream().allMatch(standing -> standing.played() == 3));
        assertThrows(IllegalStateException.class, tournament::resume);
    }
    @Test void shortenedSchedulesKeepPairingsScoresAndLengthAcrossRestarts() {
        Tournament tournament = new Tournament(UUID.randomUUID(), "Axe", "map", "kit", entries(7), false, true, 3, 10, 20, 3);
        tournament.matches().getFirst().setBestOf(7);
        tournament.matches().getFirst().finish(win(2));
        tournament.matches().get(1).start();
        tournament.resume();
        TournamentStore store = new TournamentStore(directory.resolve("shortened.yml").toFile());
        store.save(tournament);
        Tournament loaded = store.load();
        assertEquals(3, loaded.rounds());
        assertEquals(9, loaded.matches().size());
        for (int i = 0; i < tournament.matches().size(); i++) {
            TournamentMatch before = tournament.matches().get(i), after = loaded.matches().get(i);
            assertEquals(before.number(), after.number()); assertEquals(before.round(), after.round());
            assertEquals(before.first(), after.first()); assertEquals(before.second(), after.second());
            assertEquals(before.bestOf(), after.bestOf()); assertEquals(before.result(), after.result());
        }
        assertEquals(tournament.standings(), loaded.standings());
        assertEquals(TournamentMatch.Status.WAITING, loaded.matches().get(1).status());
        assertFalse(loaded.isRunning());
    }
    @Test void oldSavedTournamentsStillLoadTheFullSchedule() throws Exception {
        Tournament tournament = tournament(true, 4);
        tournament.matches().getLast().finish(win(1));
        var file = directory.resolve("legacy.yml").toFile();
        TournamentStore store = new TournamentStore(file);
        store.save(tournament);
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        yaml.set("bracket-rounds", null); yaml.save(file);
        Tournament loaded = store.load();
        assertEquals(5, loaded.rounds());
        assertEquals(15, loaded.matches().size());
        assertEquals(tournament.standings(), loaded.standings());
        assertEquals(tournament.matches().getLast().result(), loaded.matches().getLast().result());
    }
    @Test void rosterCountUsesEntriesRatherThanTeamMembersAndNeedsNoPlayerLookup() {
        assertEquals(4, RosterParser.entryCount("Alice, Bob\nCarol Dana", false));
        assertEquals(2, RosterParser.entryCount("Red: Alice, Bob\nBlue: Carol, Dana", true));
        assertThrows(IllegalArgumentException.class, () -> RosterParser.entryCount("Red Alice", true));
        assertThrows(IllegalArgumentException.class, () -> RosterParser.entryCount("Alice", false));
    }
    @Test void parsesSoloAndTeamRostersAndRejectsUnknownPlayers() {
        Map<String, RosterParser.PlayerIdentity> players = Map.of("Alice", new RosterParser.PlayerIdentity(UUID.randomUUID(), "Alice"),
                "Bob", new RosterParser.PlayerIdentity(UUID.randomUUID(), "Bob"), "Carol", new RosterParser.PlayerIdentity(UUID.randomUUID(), "Carol"));
        assertEquals(3, RosterParser.parse("Alice, Bob\nCarol", false, players::get).size());
        var teams = RosterParser.parse("Red: Alice, Bob\nBlue: Carol", true, players::get);
        assertEquals("Red", teams.getFirst().name());
        assertEquals(2, teams.getFirst().players().size());
        assertThrows(IllegalArgumentException.class, () -> RosterParser.parse("Missing", false, players::get));
        assertThrows(IllegalArgumentException.class, () -> RosterParser.parse("Red Alice", true, players::get));
    }
}
