package me.advait.contender.spectator;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** A visual entity which is never added to the world. */
public interface AllayAvatar {
    void spawn(Player viewer, Location location, Component name);
    void move(Player viewer, Location location, Component name);
    void destroy(Player viewer);
}
