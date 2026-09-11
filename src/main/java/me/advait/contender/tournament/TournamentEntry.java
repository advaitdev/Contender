package me.advait.contender.tournament;

import java.util.List;
import java.util.UUID;

public record TournamentEntry(String name, List<UUID> players) {
    public TournamentEntry {
        if (name == null || name.isBlank() || name.length() > 48) throw new IllegalArgumentException("Choose a name between 1 and 48 characters.");
        players = List.copyOf(players);
        if (players.isEmpty() || players.stream().distinct().count() != players.size()) throw new IllegalArgumentException("Each entry needs a roster without duplicate players.");
    }
}
