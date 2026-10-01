package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.LagInjector;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.entity.Player;

/** Real input delay, as if everyone's internet got worse. */
public final class LagSpike implements Sabotage {
    private final LagInjector injector;

    public LagSpike(LagInjector injector) { this.injector = injector; }

    @Override public String id() { return "lag_spike"; }
    @Override public String name() { return "Lag Spike"; }
    @Override public String description() { return "Everyone's ping jumps up. Hits land late."; }
    @Override public DialogIcon icon() { return DialogIcon.PING; }

    @Override public void apply(Player player, SabotageContext context) {
        injector.add(player, Math.clamp(context.settings().getInt("ping", 200), 20, 1000));
    }

    @Override public void remove(Player player, SabotageContext context) { injector.remove(player); }
}
