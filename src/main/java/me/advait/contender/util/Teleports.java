package me.advait.contender.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Every player teleport in Contender goes through here. Labels ride on players (the line under a name, the
 * Juggernaut crown) so they move with them perfectly, but Paper refuses to move a player who has a passenger to
 * another world. They get off first and climb back on a tick later.
 */
public final class Teleports {
    private Teleports() { }

    public static boolean to(Player player, Location destination) {
        for (Entity passenger : List.copyOf(player.getPassengers())) {
            if (Tags.isManaged(passenger)) player.removePassenger(passenger);
        }
        return player.teleport(destination);
    }
}
