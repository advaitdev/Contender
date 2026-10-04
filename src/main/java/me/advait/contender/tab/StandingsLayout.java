package me.advait.contender.tab;

import me.advait.contender.display.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** A ranked list (minigame leaderboards) in the same rows used by the tab list and the board. */
public final class StandingsLayout {
    /** One ranked player. {@code value} is shown on the right, such as a time, a score, or "Out". */
    public record Row(UUID player, String fallbackName, String value, boolean highlight, boolean faded) { }

    private static final int PER_COLUMN = 18;

    private StandingsLayout() { }

    public static BracketLayout.Layout render(String heading, List<Row> standings, Function<UUID, BracketLayout.Presence> presence, Theme theme) {
        List<TabRow> rows = new ArrayList<>();
        int columns = Math.max(1, (standings.size() + PER_COLUMN - 1) / PER_COLUMN);
        for (int column = 0; column < columns; column++) {
            rows.add(TabRow.label(Component.text(heading, theme.primary())));
            rows.add(TabRow.label(Component.text("------------------------------", theme.accent())));
            for (int i = column * PER_COLUMN; i < Math.min(standings.size(), (column + 1) * PER_COLUMN); i++) {
                Row standing = standings.get(i);
                var player = presence.apply(standing.player());
                TextColor color = standing.highlight() ? theme.primary() : standing.faded() ? theme.muted() : theme.secondary();
                Component name = Component.text((i + 1) + ". ", theme.muted()).append(player.display());
                Component text = TabText.scored(name, standing.value(), color, 210);
                rows.add(new TabRow(text, player.latency(), player.texture(), player.signature(), player.head().appendSpace().append(text), null));
            }
            if (standings.isEmpty()) rows.add(TabRow.label(Component.text("No players entered.", theme.muted())));
            while (rows.size() % 20 != 0) rows.add(TabRow.label(Component.empty()));
        }
        return new BracketLayout.Layout(List.copyOf(rows), 1, 0, 1, true);
    }
}
