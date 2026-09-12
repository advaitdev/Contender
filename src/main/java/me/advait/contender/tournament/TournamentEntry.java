package me.advait.contender.tournament;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TournamentEntry(String name, List<UUID> players, Map<UUID, String> playerNames) {
    public TournamentEntry(String name, List<UUID> players) { this(name, players, Map.of()); }
    public TournamentEntry {
        if (name == null || name.isBlank() || name.length() > 48) throw new IllegalArgumentException("Choose a name between 1 and 48 characters.");
        players = List.copyOf(players);
        playerNames = Map.copyOf(playerNames);
        for (var entry : playerNames.entrySet()) {
            if (!players.contains(entry.getKey()) || !entry.getValue().matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("Saved player names must belong to this roster.");
            }
        }
        if (players.isEmpty() || players.stream().distinct().count() != players.size()) throw new IllegalArgumentException("Each entry needs a roster without duplicate players.");
    }
}
