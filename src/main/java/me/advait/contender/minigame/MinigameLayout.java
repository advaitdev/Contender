package me.advait.contender.minigame;

import me.advait.contender.tab.BracketLayout;
import me.advait.contender.tab.TabRow;
import me.advait.contender.tab.TabText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The same ranked rows are used in tab, the board, and public previews. */
public final class MinigameLayout {
    private MinigameLayout() { }

    public static BracketLayout.Layout render(String heading, List<MinigameStanding> standings,
                                              Function<MinigameStanding, BracketLayout.Presence> presence) {
        List<TabRow> rows = new ArrayList<>();
        int columns = Math.max(1, (standings.size() + 17) / 18);
        for (int column = 0; column < columns; column++) {
            rows.add(TabRow.label(Component.text(heading, NamedTextColor.GOLD)));
            rows.add(TabRow.label(Component.text("------------------------------", NamedTextColor.DARK_AQUA)));
            for (int i = column * 18; i < Math.min(standings.size(), column * 18 + 18); i++) {
                MinigameStanding standing = standings.get(i);
                var player = presence.apply(standing);
                Component text = TabText.scored(player.display(), standing.value(), standing.color(), 210);
                Component board = player.head().appendSpace().append(text);
                rows.add(new TabRow(text, player.latency(), player.texture(), player.signature(), board, null));
            }
            if (standings.isEmpty()) rows.add(TabRow.label(Component.text("No players entered.", NamedTextColor.GRAY)));
            if (column + 1 < columns) while (rows.size() % 20 != 0) rows.add(TabRow.label(Component.empty()));
        }
        return new BracketLayout.Layout(List.copyOf(rows), 1, 0, 1, true);
    }
}
