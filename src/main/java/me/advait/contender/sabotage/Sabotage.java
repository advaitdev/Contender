package me.advait.contender.sabotage;

import me.advait.contender.dialog.DialogIcon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * A rule change a hacker can trigger for everyone in the current event. Implementations are stateless
 * apart from what they keep in the {@link SabotageContext}; the service decides who is affected.
 */
public interface Sabotage {
    String id();

    String name();

    /** One short sentence for menus and the announcement. */
    String description();

    DialogIcon icon();

    /** Once, when the sabotage starts. */
    default void start(SabotageContext context) { }

    /** Once, when the sabotage ends, after every player has been removed. */
    default void stop(SabotageContext context) { }

    /** Applied to each affected player, again after they respawn or rejoin. Must be idempotent. */
    default void apply(Player player, SabotageContext context) { }

    /** Undoes {@link #apply}. Called when the sabotage ends or the player stops being affected. */
    default void remove(Player player, SabotageContext context) { }

    /** Once per second while active. */
    default void tick(SabotageContext context, long second) { }

    /** A hit between two players, before the game decides whether it is lethal. */
    default void onHit(EntityDamageByEntityEvent event, Player attacker, Player victim, SabotageContext context) { }

    /** Any damage to an affected player, before the game decides whether it is lethal. */
    default void onDamage(EntityDamageEvent event, Player victim, SabotageContext context) { }

    /** A hit that actually landed. */
    default void afterHit(EntityDamageByEntityEvent event, Player attacker, Player victim, SabotageContext context) { }
}
