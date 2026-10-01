package me.advait.contender.minigame;

import me.advait.contender.dialog.DialogIcon;
import org.bukkit.entity.Player;

/** A kind of minigame: how to set it up and how to create one. Register new types in MinigameService. */
public interface MinigameType {
    String id();

    String name();

    /** One sentence for the event list. */
    String description();

    DialogIcon icon();

    /** Opens the dialog that creates a new game of this type. */
    void openCreate(Player director);

    /** Setup tools (courses, arenas, worlds), or false when the type needs none. */
    default boolean hasSetup() { return false; }

    default void openSetup(Player director) { }

    /** The last game, reloaded at startup so its results stay on the board. May be null. */
    default Minigame restore() { return null; }
}
