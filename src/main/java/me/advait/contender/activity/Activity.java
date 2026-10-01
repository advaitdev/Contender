package me.advait.contender.activity;

import org.bukkit.entity.Player;

/**
 * Something that takes over players: a duel, a minigame, an interview, or spectating.
 * The {@link ActivityRegistry} records which activity owns each player, so every feature
 * asks one place whether a player is free.
 */
public interface Activity {
    /** Shown in messages such as "Alice is busy in Duel #3". */
    String displayName();

    /** The player disconnected while owned. Claims survive the disconnect unless the activity releases them. */
    default void handleQuit(Player player) { }

    /** The player reconnected while still owned. */
    default void handleJoin(Player player) { }

    /** Stop immediately, return everyone to the lobby and release every claim. Must not throw. */
    void forceStop(String reason);
}
