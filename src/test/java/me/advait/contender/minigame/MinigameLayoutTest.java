package me.advait.contender.minigame;

import me.advait.contender.tab.BracketLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinigameLayoutTest {
    @Test void rankedRowsPreserveInfinityHeadsTiersAndOfflinePingAcrossColumns() {
        List<MinigameStanding> standings = new ArrayList<>();
        for (int i = 0; i < 64; i++) standings.add(new MinigameStanding(UUID.randomUUID(), "Player" + i,
                i == 0 ? "∞" : String.valueOf(64 - i), NamedTextColor.GREEN));
        var layout = MinigameLayout.render("Combo", standings, row -> new BracketLayout.Presence(row.name(),
                Component.text("(R) HT1 " + row.name()), -1, "skin", "signed", Component.text("head")));

        assertTrue(layout.rows().size() <= 80);
        var playerRows = layout.rows().stream().filter(row -> row.latency() == -1).toList();
        assertEquals(64, playerRows.size());
        assertTrue(plain(playerRows.getFirst().text()).contains("(R) HT1 Player0"));
        assertTrue(plain(playerRows.getFirst().text()).endsWith("∞"));
        assertTrue(plain(playerRows.getFirst().boardText()).startsWith("head "));
        assertEquals("skin", playerRows.getFirst().texture());
        assertEquals("signed", playerRows.getFirst().signature());
        assertTrue(playerRows.stream().allMatch(row -> row.matchNumber() == null));
        for (int i = 0; i < 4; i++) assertEquals("Combo", plain(layout.rows().get(i * 20).text()));
        assertEquals(1, layout.pages());
    }

    @Test void emptyModeStillHasAReadableLeaderboard() {
        var layout = MinigameLayout.render("Manhunt", List.of(), row -> { throw new AssertionError(); });
        assertEquals(3, layout.rows().size());
        assertEquals("Manhunt", plain(layout.rows().getFirst().text()));
        assertEquals("No players entered.", plain(layout.rows().getLast().text()));
    }

    private String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
}
