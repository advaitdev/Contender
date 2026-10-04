package me.advait.contender.arena;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** The game using a leased copy decides who may change its blocks. */
public interface ArenaActivity {
    /** A player in this activity wants to place or break a block inside the copy. */
    boolean allowBlockChange(Player player, Location at, boolean placing);

    /** Whether fluids, fire, explosions and pistons may change blocks right now. */
    boolean environmentActive();
}
