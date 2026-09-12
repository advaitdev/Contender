package me.advait.contender.hacker;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

final class HackerResistance {
    /** Apply reduction before absorption, retaining raw damage for normal hit reactions and cooldowns. */
    @SuppressWarnings("deprecation")
    static void apply(EntityDamageEvent event, Player player, double percent) {
        var magic = EntityDamageEvent.DamageModifier.MAGIC;
        var absorption = EntityDamageEvent.DamageModifier.ABSORPTION;
        if (!event.isApplicable(magic)) { event.setDamage(event.getDamage() * (1 - percent / 100)); return; }
        double beforeAbsorption = Math.max(0, event.getFinalDamage() - (event.isApplicable(absorption) ? event.getDamage(absorption) : 0));
        double reduction = beforeAbsorption * percent / 100;
        event.setDamage(magic, event.getDamage(magic) - reduction);
        if (event.isApplicable(absorption)) event.setDamage(absorption, -Math.min(player.getAbsorptionAmount(), beforeAbsorption - reduction));
    }
    private HackerResistance() { }
}
