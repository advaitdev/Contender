package me.advait.contender.duel;

import org.bukkit.Bukkit;

import java.util.*;

public class DuelTeam {

    private final String name;
    private final List<UUID> players;
    private final Set<UUID> alivePlayers;
    private int score;

    public DuelTeam(String name) {
        this.name = name;
        this.players = new ArrayList<>();
        this.alivePlayers = new HashSet<>();
        this.score = 0;
    }

    public void addPlayer(UUID uuid) {
        if (!players.contains(uuid)) {
            players.add(uuid);
        }
    }

    public void removePlayer(UUID uuid) {
        players.remove(uuid);
        alivePlayers.remove(uuid);
    }

    public void markDead(UUID uuid) {
        alivePlayers.remove(uuid);
    }

    public boolean isEliminated() {
        return alivePlayers.isEmpty();
    }

    public void resetAlive() {
        alivePlayers.clear();
        alivePlayers.addAll(players);
    }

    public void incrementScore() {
        score++;
    }

    public String getName() {
        if (players.size() == 1) return Bukkit.getOfflinePlayer(players.getFirst()).getName();
        return name;
    }

    public List<UUID> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public Set<UUID> getAlivePlayers() {
        return Collections.unmodifiableSet(alivePlayers);
    }

    public int getScore() {
        return score;
    }

    public boolean hasPlayer(UUID uuid) {
        return players.contains(uuid);
    }

    public int size() {
        return players.size();
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    public void clearPlayers() {
        players.clear();
        alivePlayers.clear();
    }
}
