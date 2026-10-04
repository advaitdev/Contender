package me.advait.contender.duel;

import java.util.*;

/** One side of a match: its players, who is still standing this round, and its round wins. */
public final class DuelTeam {
    private final int number;
    private final String name;
    private final List<UUID> players;
    private final Set<UUID> alive = new LinkedHashSet<>();
    private int score;

    DuelTeam(int number, String name, List<UUID> players) {
        this.number = number;
        this.name = name;
        this.players = List.copyOf(players);
    }

    public int number() { return number; }
    public String name() { return name; }
    public List<UUID> players() { return players; }
    public int score() { return score; }
    public boolean has(UUID player) { return players.contains(player); }
    public boolean isAlive(UUID player) { return alive.contains(player); }
    public boolean anyAlive() { return !alive.isEmpty(); }
    public Set<UUID> alive() { return Collections.unmodifiableSet(alive); }

    void revive(Collection<UUID> standing) { alive.clear(); alive.addAll(standing); }
    boolean down(UUID player) { return alive.remove(player); }
    void addPoint() { score++; }
    void setScore(int score) { this.score = score; }
}
