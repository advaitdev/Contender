package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/** Every landed hit heals the attacker. */
public final class Vampire implements Sabotage {
    @Override public String id() { return "vampire"; }
    @Override public String name() { return "Vampire"; }
    @Override public String description() { return "Hitting someone heals you."; }
    @Override public DialogIcon icon() { return DialogIcon.BLOOD; }

    @Override public void afterHit(EntityDamageByEntityEvent event, Player attacker, Player victim, SabotageContext context) {
        if (!context.affects(attacker) || attacker.isDead()) return;
        double share = Math.clamp(context.settings().getDouble("heal-share", 0.35), 0, 1);
        var max = attacker.getAttribute(Attribute.MAX_HEALTH);
        double room = (max == null ? 20 : max.getValue()) - attacker.getHealth();
        double amount = Math.min(room, event.getFinalDamage() * share);
        if (amount > 0) attacker.heal(amount, EntityRegainHealthEvent.RegainReason.MAGIC);
    }
}
