package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.List;

/** The floating scoreboard above a match: a "VS" card before round one and the score between rounds. */
final class DuelHud {
    private final Contender plugin;
    private final Location anchor;
    private TextDisplay card;

    DuelHud(Contender plugin, Location team1, Location team2) {
        this.plugin = plugin;
        Location middle = team1.clone().add(team2).multiply(0.5);
        middle.setY(Math.max(team1.getY(), team2.getY()) + 3.2);
        middle.setWorld(team1.getWorld());
        anchor = middle;
    }

    void versus(List<DuelTeam> teams, int winsNeeded) {
        Theme theme = plugin.getThemes().current();
        Component names = Component.empty();
        for (int i = 0; i < teams.size(); i++) {
            if (i > 0) names = names.append(Component.text(teams.size() > 2 ? ", " : "  vs  ", theme.muted()));
            names = names.append(Component.text(teams.get(i).name(), i % 2 == 0 ? theme.primary() : theme.secondary()));
        }
        show(names.appendNewline().append(Component.text(winsNeeded == 1 ? "One round" : "First to " + winsNeeded, theme.muted())));
    }

    void score(List<DuelTeam> teams, DuelTeam roundWinner) {
        Theme theme = plugin.getThemes().current();
        Component line = Component.empty();
        for (int i = 0; i < teams.size(); i++) {
            DuelTeam team = teams.get(i);
            if (i > 0) line = line.append(Component.text("  -  ", theme.muted()));
            var color = team == roundWinner ? theme.primary() : theme.secondary();
            line = teams.size() == 2 && i == 1
                    ? line.append(Component.text(team.score() + " ", color)).append(Component.text(team.name(), color))
                    : line.append(Component.text(team.name() + " ", color)).append(Component.text(Integer.toString(team.score()), color));
        }
        Component caption = roundWinner == null ? Component.text("Draw", theme.muted())
                : Component.text(roundWinner.name() + " takes the round", theme.muted());
        show(line.appendNewline().append(caption));
    }

    private void show(Component text) {
        if (anchor.getWorld() == null) return;
        if (card == null || !card.isValid()) {
            card = Holograms.text(anchor, text, 0.01f, Display.Billboard.CENTER, plugin.getThemes().current().background(35), "duel_hud");
            card.setViewRange(1.2f);
        } else {
            card.text(text);
            Holograms.animate(card, Holograms.scaled(0.01f), 1);
        }
        TextDisplay target = card;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(target, Holograms.scaled(2.1f), 6), 2L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(target, Holograms.scaled(1.8f), 4), 9L);
    }

    void hide() {
        TextDisplay target = card;
        card = null;
        if (target == null || !target.isValid()) return;
        Holograms.animate(target, Holograms.scaled(0.01f), 5);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.remove(target), 7L);
    }

    void remove() {
        Holograms.remove(card);
        card = null;
    }
}
