package me.advait.contender.race;

import me.advait.contender.tab.BracketLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceLayoutTest {
    @Test void sixtyFourRacersFitWithHeadsTiersTimesAndOfflinePings() {
        var roster = new ArrayList<RaceRun.Racer>();
        for (int i = 0; i < 64; i++) roster.add(new RaceRun.Racer(UUID.randomUUID(), "Player" + i));
        var run = new RaceRun(UUID.randomUUID(), "Mace", "map", 15, 0, roster); run.countdown(1); run.start(0);
        run.hit(roster.getFirst().id(), 2, 12_340_000_000L);
        var layout = RaceLayout.render(run, r -> new BracketLayout.Presence(r.name(), Component.text("LT1 " + r.name()), -1, "skin", "signed", Component.text("head")), 15_000_000_000L);
        assertTrue(layout.rows().size() <= 80);
        var rows = layout.rows().stream().filter(r -> r.latency() == -1).toList(); assertEquals(64, rows.size());
        assertTrue(plain(rows.getFirst().text()).contains("LT1 Player0")); assertTrue(plain(rows.getFirst().text()).contains("0:12.340"));
        assertTrue(plain(rows.getFirst().boardText()).startsWith("head ")); assertEquals("skin", rows.getFirst().texture());
        assertEquals(1, layout.pages());
    }
    private String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }
}
