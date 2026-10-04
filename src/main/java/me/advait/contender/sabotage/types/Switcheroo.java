package me.advait.contender.sabotage.types;

import me.advait.contender.util.Teleports;

import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelTeam;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Opponents trade places mid-fight. */
public final class Switcheroo implements Sabotage {
    @Override public String id() { return "switcheroo"; }
    @Override public String name() { return "Switcheroo"; }
    @Override public String description() { return "Opponents swap places every so often."; }
    @Override public DialogIcon icon() { return DialogIcon.REFRESH; }

    @Override public void tick(SabotageContext context, long second) {
        int interval = Math.clamp(context.settings().getInt("interval-seconds", 25), 5, 300);
        if (second == 0 || second % interval != 0) return;
        for (Duel duel : context.plugin().getDuels().duels()) {
            if (duel.phase() != Duel.Phase.FIGHTING || duel.teams().size() < 2) continue;
            Player first = pick(duel.teams().get(0), context), second2 = pick(duel.teams().get(1), context);
            if (first != null && second2 != null) swap(first, second2);
        }
        // Minigames have no teams: shuffle everyone still in play and swap them in pairs.
        var game = context.plugin().getMinigames().current();
        if (game != null && game.state() == me.advait.contender.minigame.Minigame.State.RUNNING) {
            List<Player> pool = new ArrayList<>();
            for (Player player : game.playing()) {
                if (player.getGameMode() != org.bukkit.GameMode.SPECTATOR && !player.isDead() && context.affects(player)) pool.add(player);
            }
            java.util.Collections.shuffle(pool);
            for (int i = 0; i + 1 < pool.size(); i += 2) swap(pool.get(i), pool.get(i + 1));
        }
    }

    private static void swap(Player first, Player second) {
        if (!first.getWorld().equals(second.getWorld())) return;
        Location a = first.getLocation(), b = second.getLocation();
        Vector va = first.getVelocity(), vb = second.getVelocity();
        Teleports.to(first, b);
        Teleports.to(second, a);
        first.setVelocity(vb);
        second.setVelocity(va);
        for (Player player : List.of(first, second)) {
            Msg.title(player, Msg.text("Switcheroo!", DialogPalette.WARNING), Component.empty(), 0, 20, 5);
            Sounds.WHOOSH.play(player);
        }
    }

    private static Player pick(DuelTeam team, SabotageContext context) {
        List<Player> options = new ArrayList<>();
        for (var id : team.alive()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && context.affects(player)) options.add(player);
        }
        return options.isEmpty() ? null : options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }
}
