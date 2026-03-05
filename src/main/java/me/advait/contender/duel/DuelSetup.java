package me.advait.contender.duel;

import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DuelSetup {

    private final UUID creator;
    private Kit selectedKit;
    private ArenaMap selectedMap;
    private final DuelTeam team1;
    private final DuelTeam team2;
    private int rounds;
    private int preRoundDelay;

    public DuelSetup(UUID creator) {
        this.creator = creator;
        this.team1 = new DuelTeam("Team 1");
        this.team2 = new DuelTeam("Team 2");
        this.rounds = 3;
        this.preRoundDelay = 10;
    }

    public boolean isValid() {
        return selectedKit != null
                && selectedMap != null
                && !team1.isEmpty()
                && !team2.isEmpty();
    }

    public UUID getCreator() {
        return creator;
    }

    public Kit getSelectedKit() {
        return selectedKit;
    }

    public void setSelectedKit(Kit selectedKit) {
        this.selectedKit = selectedKit;
    }

    public ArenaMap getSelectedMap() {
        return selectedMap;
    }

    public void setSelectedMap(ArenaMap selectedMap) {
        this.selectedMap = selectedMap;
    }

    public DuelTeam getTeam1() {
        return team1;
    }

    public DuelTeam getTeam2() {
        return team2;
    }

    public int getRounds() {
        return rounds;
    }

    public void setRounds(int rounds) {
        this.rounds = Math.max(1, Math.min(rounds, 15));
    }

    public int getPreRoundDelay() {
        return preRoundDelay;
    }

    public void setPreRoundDelay(int preRoundDelay) {
        this.preRoundDelay = Math.max(5, Math.min(preRoundDelay, 60));
    }

    public boolean isPlayerInAnyTeam(UUID uuid) {
        return team1.hasPlayer(uuid) || team2.hasPlayer(uuid);
    }

    public Set<UUID> getAllPlayers() {
        Set<UUID> all = new HashSet<>(team1.getPlayers());
        all.addAll(team2.getPlayers());
        return all;
    }
}
