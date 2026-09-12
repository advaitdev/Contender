package me.advait.contender.race;

import me.advait.contender.tab.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;
import java.util.function.Function;

/** Same rows feed the tab transport and the in-world board. */
public final class RaceLayout {
    private RaceLayout() { }
    public static BracketLayout.Layout render(RaceRun run, Function<RaceRun.Racer, BracketLayout.Presence> presence, long now) {
        List<TabRow> rows = new ArrayList<>();
        var racers = run.standings();
        int columns = (racers.size() + 17) / 18;
        for (int column = 0; column < columns; column++) {
            rows.add(TabRow.label(Component.text("Mace Race", NamedTextColor.GOLD)));
            rows.add(TabRow.label(Component.text("------------------------------", NamedTextColor.DARK_AQUA)));
            for (int i = column * 18; i < Math.min(racers.size(), column * 18 + 18); i++) {
                var racer = racers.get(i); var p = presence.apply(racer);
                String value = racer.finishNanos() >= 0 ? RaceRun.time(racer.finishNanos())
                        : racer.withdrawn() ? "DNF" : run.state() == RaceRun.State.READY ? "Waiting"
                        : run.state() == RaceRun.State.COUNTDOWN ? "Starting" : "#" + racer.checkpoint() + "  " + RaceRun.time(run.elapsed(now)).replaceFirst("\\.[0-9]{3}$", "");
                Component name = Component.text(racer.place() > 0 ? racer.place() + ". " : "", NamedTextColor.GOLD).append(p.display());
                NamedTextColor color = racer.finishNanos() >= 0 ? NamedTextColor.GREEN : racer.withdrawn() ? NamedTextColor.GRAY : NamedTextColor.GOLD;
                Component text = TabText.scored(name, value, color, 210);
                Component board = p.head().appendSpace().append(text);
                rows.add(new TabRow(text, p.latency(), p.texture(), p.signature(), board, null));
            }
            if (column + 1 < columns) while (rows.size() % 20 != 0) rows.add(TabRow.label(Component.empty()));
        }
        return new BracketLayout.Layout(List.copyOf(rows), 1, 0, 1, true);
    }
}
