package me.advait.contender.tournament;

import me.advait.contender.tournament.RosterParser.PlayerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class OfflineRosterTest {
    @TempDir Path directory;

    @Test void resolvedOfflineRostersSurviveRestartAndWaitForEveryTeamMember() {
        Map<String, PlayerIdentity> identities = new HashMap<>();
        for (String name : List.of("Alice", "Bob", "Carol", "Dana")) {
            identities.put(name, new PlayerIdentity(UUID.randomUUID(), name));
        }
        for (boolean teams : List.of(false, true)) {
            String text = teams ? "Red: Alice, Bob\nBlue: Carol, Dana" : "Alice, Carol";
            var entries = RosterParser.parseAsync(text, teams, name -> CompletableFuture.completedFuture(identities.get(name))).join();
            Tournament tournament = new Tournament(UUID.randomUUID(), "Friday", "forest", "sword", entries, teams, false, 5, 10, 20);
            TournamentStore store = new TournamentStore(directory.resolve("tournament.yml").toFile());
            store.save(tournament);
            Tournament loaded = store.load();

            assertEquals(entries, loaded.entries());
            for (var entry : loaded.entries()) {
                for (UUID id : entry.players()) assertNotNull(entry.playerNames().get(id), "Offline team members keep their names across restart");
            }
            assertFalse(loaded.isRunning());
            var layout = me.advait.contender.tab.BracketLayout.render(loaded, Map.of(), id -> {
                String name = loaded.entries().stream().map(e -> e.playerNames().get(id)).filter(Objects::nonNull).findFirst().orElseThrow();
                return new me.advait.contender.tab.BracketLayout.Presence(name, net.kyori.adventure.text.Component.text(name), -1, null, null);
            }, loaded.entries().stream().flatMap(e -> e.players().stream()).toList(), me.advait.contender.tab.BracketLayout.View.following());
            String board = layout.rows().stream().map(row -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(row.boardText())).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(board.contains("Alice") && board.contains("Carol"));
            if (teams) assertTrue(board.contains("Bob") && board.contains("Dana"));
            loaded.resume();
            assertNull(loaded.nextMatch(id -> false));
            UUID missing = identities.get(teams ? "Bob" : "Carol").id();
            assertNull(loaded.nextMatch(id -> !id.equals(missing)));
            assertTrue(loaded.matches().stream().allMatch(m -> m.status() == TournamentMatch.Status.WAITING));
            assertNotNull(loaded.nextMatch(id -> true));
        }
    }

    @Test void asynchronousCompletionPreservesTheEnteredOrderAndNames() {
        Map<String, CompletableFuture<PlayerIdentity>> lookups = new HashMap<>();
        var result = RosterParser.parseAsync("alice, BOB, carol", false,
                name -> lookups.computeIfAbsent(name, ignored -> new CompletableFuture<>()));
        lookups.get("carol").complete(new PlayerIdentity(UUID.randomUUID(), "Carol"));
        lookups.get("BOB").complete(new PlayerIdentity(UUID.randomUUID(), "Bob"));
        assertFalse(result.isDone());
        lookups.get("alice").complete(new PlayerIdentity(UUID.randomUUID(), "Alice"));
        assertEquals(List.of("Alice", "Bob", "Carol"), result.join().stream().map(TournamentEntry::name).toList());
    }

    @Test void oneFailedLookupRejectsTheWholeRoster() {
        var result = RosterParser.parseAsync("Alice, Missing", false, name -> name.equals("Alice")
                ? CompletableFuture.completedFuture(new PlayerIdentity(UUID.randomUUID(), "Alice"))
                : CompletableFuture.failedFuture(new IllegalArgumentException("Couldn't look up Missing.")));
        assertThrows(CompletionException.class, result::join);
    }

    @Test void differentlyCapitalizedDuplicatesCannotBecomeDifferentContestants() {
        List<String> requests = new ArrayList<>();
        PlayerIdentity alice = new PlayerIdentity(UUID.randomUUID(), "Alice");
        var entries = RosterParser.parseAsync("Red: Alice\nBlue: ALICE", true, name -> {
            requests.add(name);
            return CompletableFuture.completedFuture(alice);
        }).join();
        assertEquals(List.of("Alice"), requests);
        assertThrows(IllegalArgumentException.class, () -> new Tournament(UUID.randomUUID(), "Friday", "forest", "sword",
                entries, true, false, 3, 10, 20));
    }

    @Test void malformedTeamsAndOversizedRostersAreRejectedBeforeLookingUpPlayers() {
        for (String text : List.of("Red: Alice\nBlue", "Red: Alice\nBlue:", "Alice")) {
            assertThrows(IllegalArgumentException.class, () -> RosterParser.parseAsync(text, true, name -> {
                fail("Invalid rosters must not start profile lookups");
                return null;
            }));
        }
        String tooMany = String.join(",", Collections.nCopies(65, "Alice"));
        assertThrows(IllegalArgumentException.class, () -> RosterParser.parseAsync(tooMany, false, name -> {
            fail("Oversized rosters must not start profile lookups");
            return null;
        }));
    }
}
