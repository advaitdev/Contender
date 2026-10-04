package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Keeps a hidden, endless potion effect on affected players. Kits that clear effects get it back within a second. */
public class EffectSabotage implements Sabotage {
    private final String id, name, description;
    private final DialogIcon icon;
    private final PotionEffectType type;
    private final int amplifier;

    public EffectSabotage(String id, String name, String description, DialogIcon icon, PotionEffectType type, int amplifier) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.type = type;
        this.amplifier = amplifier;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public DialogIcon icon() { return icon; }

    @Override public void apply(Player player, SabotageContext context) {
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && current.isInfinite() && current.getAmplifier() == amplifier) return;
        player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, true, false, false));
    }

    @Override public void tick(SabotageContext context, long second) {
        for (Player player : context.affectedPlayers()) if (!player.isDead()) apply(player, context);
    }

    @Override public void remove(Player player, SabotageContext context) {
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && current.isInfinite()) player.removePotionEffect(type);
    }
}
