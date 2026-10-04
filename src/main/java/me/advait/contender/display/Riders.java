package me.advait.contender.display;

import org.bukkit.entity.Display;
import org.bukkit.entity.Player;

/**
 * Labels ride on their players, so every client draws them moving exactly with the player. Following with
 * teleports always trails behind. A teleport, a death or a world change knocks a rider off; {@link #keep} puts it
 * back, and is meant to be called every tick.
 */
public final class Riders {
    private Riders() { }

    public static void keep(Player owner, Display rider) {
        if (!rider.isValid() || owner.isDead() || owner.getPassengers().contains(rider)) return;
        if (!rider.getWorld().equals(owner.getWorld()) || rider.getLocation().distanceSquared(owner.getLocation()) > 4) {
            rider.teleport(owner.getLocation());
        }
        owner.addPassenger(rider);
    }
}
