package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/** Everyone takes extra damage from every source. */
public final class Fragile implements Sabotage {
    @Override public String id() { return "fragile"; }
    @Override public String name() { return "Fragile"; }
    @Override public String description() { return "Everyone takes 50% more damage."; }
    @Override public DialogIcon icon() { return DialogIcon.SKULL; }

    @Override public void onDamage(EntityDamageEvent event, Player victim, SabotageContext context) {
        if (!context.affects(victim) || event.getCause() == EntityDamageEvent.DamageCause.VOID) return;
        double multiplier = Math.clamp(context.settings().getDouble("multiplier", 1.5), 1, 5);
        event.setDamage(event.getDamage() * multiplier);
    }
}
