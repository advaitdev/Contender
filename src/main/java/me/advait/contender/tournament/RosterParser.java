package me.advait.contender.tournament;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class RosterParser {
    public record PlayerIdentity(UUID id, String name) { }
    private record NamedEntry(String name, List<String> players) { }
    private RosterParser() { }
    public static List<TournamentEntry> parse(String text, boolean teams, Function<String, PlayerIdentity> findPlayer) {
        return resolveEntries(readEntries(text, teams), findPlayer);
    }

    /** Resolves a tournament roster in full before producing entries, preserving its input order. */
    public static CompletableFuture<List<TournamentEntry>> parseAsync(String text, boolean teams,
            Function<String, CompletableFuture<PlayerIdentity>> findPlayer) {
        List<NamedEntry> entries = readEntries(text, teams);
        if (entries.size() < 2 || entries.size() > 64) throw new IllegalArgumentException("Enter between 2 and 64 players or teams.");
        Map<String, CompletableFuture<PlayerIdentity>> lookups = new LinkedHashMap<>();
        for (NamedEntry entry : entries) {
            for (String name : entry.players()) {
                lookups.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> findPlayer.apply(name));
            }
        }
        return CompletableFuture.allOf(lookups.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> resolveEntries(entries, name -> lookups.get(name.toLowerCase(Locale.ROOT)).join()));
    }

    private static List<TournamentEntry> resolveEntries(List<NamedEntry> names, Function<String, PlayerIdentity> findPlayer) {
        List<TournamentEntry> entries = new ArrayList<>();
        for (NamedEntry entry : names) {
            List<PlayerIdentity> players = entry.players().stream().map(name -> {
                PlayerIdentity player = findPlayer.apply(name);
                if (player == null) throw new IllegalArgumentException("Couldn't find player " + name + ".");
                return player;
            }).toList();
            entries.add(new TournamentEntry(entry.name() == null ? players.getFirst().name() : entry.name(),
                    players.stream().map(PlayerIdentity::id).toList()));
        }
        return List.copyOf(entries);
    }

    private static List<NamedEntry> readEntries(String text, boolean teams) {
        List<NamedEntry> entries = new ArrayList<>();
        if (teams) {
            for (String line : text.split("\\R")) {
                if (line.isBlank()) continue;
                String[] parts = line.split(":", 2);
                if (parts.length != 2) throw new IllegalArgumentException("Write each team as Name: player1, player2.");
                String name = parts[0].strip();
                if (name.isBlank() || name.length() > 48) throw new IllegalArgumentException("Choose a team name between 1 and 48 characters.");
                List<String> players = playerNames(parts[1]);
                if (players.isEmpty()) throw new IllegalArgumentException("Add at least one player to " + name + ".");
                entries.add(new NamedEntry(name, players));
            }
        } else {
            for (String player : playerNames(text)) entries.add(new NamedEntry(null, List.of(player)));
        }
        return List.copyOf(entries);
    }
    private static List<String> playerNames(String text) {
        return Arrays.stream(text.strip().split("[,\\s]+")).filter(name -> !name.isBlank()).toList();
    }
}
