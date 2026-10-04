package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Hunger drains fast. Kits that keep hunger full are unaffected. */
public final class Famished implements Sabotage {
    @Override public String id() { return "famished"; }
    @Override public String name() { return "Famished"; }
    @Override public String description() { return "Hunger drains fast, so healing stops."; }
    @Override public DialogIcon icon() { return DialogIcon.HUNGER; }

    @Override public void tick(SabotageContext context, long second) {
        for (Player player : context.affectedPlayers()) {
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setExhaustion(Math.min(40f, player.getExhaustion() + 1.6f));
            }
        }
    }
}
