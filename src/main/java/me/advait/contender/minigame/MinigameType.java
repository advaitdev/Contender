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

    /**
     * Creates a game without a dialog (the dialog calls this too). {@code options} holds the game's own
     * settings by key, such as "lives" or "minutes"; missing keys use defaults.
     */
    Minigame create(String name, me.advait.contender.map.ArenaMap map, me.advait.contender.kit.Kit kit,
                    java.util.Map<java.util.UUID, String> roster, java.util.Map<String, String> options);

    /** Setup tools (courses, arenas, worlds), or false when the type needs none. */
    default boolean hasSetup() { return false; }

    default void openSetup(Player director) { }

    /** The last game, reloaded at startup so its results stay on the board. May be null. */
    default Minigame restore() { return null; }
}
