package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** The lights flicker: everyone's screen goes dark for a few seconds now and then. */
public final class Blackout implements Sabotage {
    @Override public String id() { return "blackout"; }
    @Override public String name() { return "Blackout"; }
    @Override public String description() { return "Screens go dark every few seconds."; }
    @Override public DialogIcon icon() { return DialogIcon.PHANTOM; }

    @Override public void tick(SabotageContext context, long second) {
        int interval = Math.clamp(context.settings().getInt("interval-seconds", 15), 5, 300);
        if (second % interval != 1) return;
        for (Player player : context.affectedPlayers()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0, true, false, false));
        }
    }

    @Override public void remove(Player player, SabotageContext context) {
        PotionEffect darkness = player.getPotionEffect(PotionEffectType.DARKNESS);
        if (darkness != null && darkness.getDuration() <= 120) player.removePotionEffect(PotionEffectType.DARKNESS);
    }
}
