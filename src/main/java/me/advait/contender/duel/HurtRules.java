package me.advait.contender.duel;

import org.bukkit.event.entity.EntityDamageEvent;

public final class HurtRules {
    private HurtRules() { }

    @SuppressWarnings("deprecation") // No replacement exposes the final health and absorption reductions.
    public static void removeHealthDamage(EntityDamageEvent event) {
        if (event.isApplicable(EntityDamageEvent.DamageModifier.MAGIC)) {
            if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
                event.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION, 0);
            }
            // Preserve raw damage: Paper uses it for hurt cooldowns and normal knockback.
            var reduction = EntityDamageEvent.DamageModifier.MAGIC;
            event.setDamage(reduction, event.getDamage(reduction) - event.getFinalDamage());
        } else {
            event.setDamage(0);
        }
    }
}
