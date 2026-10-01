package me.advait.contender.duel;

import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;

/**
 * How a match is played.
 *
 * @param winsNeeded       round wins needed to take the match
 * @param sortSeconds      first-round countdown, during which players can arrange their inventory
 * @param freeForAll       every player fights alone; otherwise exactly two teams
 * @param reconnectSeconds how long a disconnected side has to return before it forfeits
 */
public record DuelSettings(ArenaMap map, Kit kit, int winsNeeded, int sortSeconds, boolean freeForAll, int reconnectSeconds) {
    public DuelSettings {
        java.util.Objects.requireNonNull(map, "map");
        java.util.Objects.requireNonNull(kit, "kit");
        if (winsNeeded < 1 || winsNeeded > 8) throw new IllegalArgumentException("Choose 1 to 8 round wins.");
        if (sortSeconds < 3 || sortSeconds > 60) throw new IllegalArgumentException("Choose a sorting time from 3 to 60 seconds.");
        if (reconnectSeconds < 0 || reconnectSeconds > 600) throw new IllegalArgumentException("Choose a reconnect time from 0 to 600 seconds.");
    }
}
