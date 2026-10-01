package me.advait.contender.stage;

import me.advait.contender.tab.BracketLayout;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * One event of the show: a round robin or a minigame. Exactly one stage is selected at a time;
 * the tab list and board show it, and hack plans and sabotages last as long as it runs.
 */
public interface Stage {
    UUID id();

    String name();

    /** "Round Robin", "Mace Race", ... */
    String kind();

    String statusText();

    /** Matches or play have begun at least once. */
    boolean started();

    /** Finished or cancelled; a new stage can be created. */
    boolean finished();

    boolean cancelled();

    /** Entered in this stage's roster. */
    boolean involves(UUID player);

    /**
     * Rows for the tab list and the board.
     *
     * @param others players who are not entered but should still be listed (for example spectators online now)
     */
    BracketLayout.Layout layout(BracketLayout.View view, Function<UUID, BracketLayout.Presence> presence, List<UUID> others);

    /** Short footer, such as "Round 2" or "Finish Times". */
    String caption(BracketLayout.Layout layout);

    /** Whether the bracket views (rounds, standings) apply. */
    default boolean hasRounds() { return false; }

    /** Rounds available in the bracket view. */
    default int rounds() { return 1; }
}
