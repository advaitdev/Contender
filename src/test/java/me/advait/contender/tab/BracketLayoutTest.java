package me.advait.contender.tab;

import me.advait.contender.duel.DuelResult;
import me.advait.contender.tournament.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class BracketLayoutTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    static Tournament tournament(int size) {
        List<TournamentEntry> entries = new ArrayList<>();
        for (int i = 0; i < size; i++) entries.add(new TournamentEntry("Player" + i, List.of(new UUID(0, i + 1))));
        return new Tournament(UUID.randomUUID(), "Sword stage", "arena", "sword", entries, false, false, 3, 10, 20);
    }
    static List<UUID> roster(Tournament t) { return t.entries().stream().flatMap(e -> e.players().stream()).toList(); }
    static Function<UUID, BracketLayout.Presence> online(Tournament t) {
        Map<UUID, String> names = new HashMap<>();
        t.entries().forEach(e -> e.players().forEach(id -> names.put(id, e.name())));
        return id -> new BracketLayout.Presence(names.get(id), Component.text(names.get(id)), 42, "texture", "signature");
    }
    @Test void everyMatchInEveryRoundIsReachableWithoutSplittingOrExceedingClientLimit() {
        for (int count : List.of(2, 3, 10, 25, 64)) {
            Tournament t = tournament(count);
            Set<Integer> seen = new HashSet<>();
            for (int round = 1; round <= BracketLayout.rounds(t); round++) {
                var first = BracketLayout.render(t, Map.of(), online(t), roster(t), new BracketLayout.View(round, 0, false));
                Set<String> rosterSeen = new HashSet<>();
                for (int page = 0; page < first.pages(); page++) {
                    var layout = BracketLayout.render(t, Map.of(), online(t), roster(t), new BracketLayout.View(round, page, false));
                    assertTrue(layout.rows().size() <= 80);
                    assertEquals(0, layout.rows().size() % 20);
                    int rosterStart = layout.rows().size() - 20;
                    for (int i = 0; i < rosterStart; i++) {
                        String line = PLAIN.serialize(layout.rows().get(i).text());
                        if (line.startsWith("#")) {
                            seen.add(Integer.parseInt(line.substring(1, line.indexOf(' '))));
                            assertTrue(i % 20 <= 16, "A matchup must fit in one column");
                            assertTrue(PLAIN.serialize(layout.rows().get(i + 1).text()).contains("Player"));
                            assertTrue(PLAIN.serialize(layout.rows().get(i + 2).text()).contains("Player"));
                            assertTrue(PLAIN.serialize(layout.rows().get(i + 3).text()).startsWith("------"));
                        }
                    }
                    for (int i = rosterStart + 1; i < layout.rows().size(); i++) {
                        String name = PLAIN.serialize(layout.rows().get(i).text());
                        if (!name.isEmpty()) rosterSeen.add(name);
                    }
                }
                assertEquals(count, rosterSeen.size(), "The bye and offline entries must remain reachable");
            }
            assertEquals(t.matches().size(), seen.size());
        }
    }
    @Test void liveScoresRefreshAndFinalForfeitShowsTheWinner() {
        Tournament t = tournament(2);
        TournamentMatch match = t.matches().getFirst(); match.start();
        var layout = BracketLayout.render(t, Map.of(1, new BracketLayout.Score(2, 1)), online(t), roster(t), BracketLayout.View.following());
        assertTrue(PLAIN.serialize(layout.rows().get(2).text()).endsWith("2"));
        assertTrue(PLAIN.serialize(layout.rows().get(3).text()).endsWith("1"));
        match.finish(new DuelResult(DuelResult.Reason.FORFEIT, 2, 1, 1));
        layout = BracketLayout.render(t, Map.of(), online(t), roster(t), BracketLayout.View.following());
        assertTrue(PLAIN.serialize(layout.rows().get(1).text()).contains("Forfeit"));
        assertEquals(NamedTextColor.GREEN, layout.rows().get(2).text().children().getLast().color());
    }
    @Test void followsEarliestUnfinishedRoundAndKeepsManualRoundSelection() {
        Tournament t = tournament(4);
        assertEquals(1, BracketLayout.currentRound(t));
        t.matches().stream().filter(m -> m.round() == 1).forEach(m -> m.finish(new DuelResult(DuelResult.Reason.FORFEIT, 0, 0, 1)));
        assertEquals(2, BracketLayout.currentRound(t));
        var layout = BracketLayout.render(t, Map.of(), online(t), roster(t), new BracketLayout.View(1, -1, false));
        assertEquals(1, layout.round()); assertEquals(0, layout.page());
    }
    @Test void disconnectedPlayerKeepsNameSkinAndAnXInBothMatchAndRoster() {
        Tournament t = tournament(2);
        UUID absent = roster(t).getFirst();
        Function<UUID, BracketLayout.Presence> people = id -> {
            var p = online(t).apply(id);
            return new BracketLayout.Presence(p.name(), p.display(), id.equals(absent) ? -1 : 55, p.texture(), p.signature());
        };
        var layout = BracketLayout.render(t, Map.of(), people, roster(t), BracketLayout.View.following());
        assertEquals(-1, layout.rows().get(2).latency());
        assertEquals(-1, layout.rows().get(21).latency());
        assertEquals("texture", layout.rows().get(2).texture());
        assertEquals(55, layout.rows().get(3).latency());
    }
    @Test void teamsUseScoresAndGoDisconnectedIfOneMemberIsMissing() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var c = UUID.randomUUID();
        var t = new Tournament(UUID.randomUUID(), "Teams", "map", "kit",
                List.of(new TournamentEntry("Red", List.of(a, b)), new TournamentEntry("Blue", List.of(c))), true, true, 3, 10, 2);
        Function<UUID, BracketLayout.Presence> people = id -> new BracketLayout.Presence("Member", Component.text("Member"), id.equals(b) ? -1 : 30, null, null);
        var layout = BracketLayout.render(t, Map.of(), people, roster(t), BracketLayout.View.following());
        assertTrue(PLAIN.serialize(layout.rows().get(2).text()).startsWith("Red"));
        assertEquals(-1, layout.rows().get(2).latency());
        assertEquals(30, layout.rows().get(3).latency());
        assertTrue(PLAIN.serialize(layout.rows().get(3).text()).startsWith("Blue"));
    }
    @Test void boardAndTabKeepHeadsTierPrefixesAndMatchTargetsWithoutFormatAbbreviations() {
        Tournament t = tournament(2);
        var layout = BracketLayout.render(t, Map.of(), id -> {
            var person = online(t).apply(id);
            return new BracketLayout.Presence(person.name(), Component.text("(R)HT1 ", NamedTextColor.GRAY).append(person.display()),
                    person.latency(), person.texture(), person.signature(), me.advait.contender.util.StringUtil.getPlayerHead(id));
        }, roster(t), BracketLayout.View.following());
        TabRow row = layout.rows().get(2);
        assertEquals(1, row.matchNumber()); assertTrue(PLAIN.serialize(row.text()).contains("(R)HT1 Player0"));
        assertTrue(row.boardText().children().getFirst() instanceof net.kyori.adventure.text.ObjectComponent);
        assertTrue(PLAIN.serialize(row.boardText()).endsWith(PLAIN.serialize(row.text())));
        assertFalse(PLAIN.serialize(layout.rows().get(1).text()).contains("Bo"));
    }
    @Test void scoresStayAlignedCloserToNamesAndCancelledRowsNeverSayPlaying() {
        var t = tournament(2); t.matches().getFirst().start();
        var layout = BracketLayout.render(t, Map.of(), online(t), roster(t), BracketLayout.View.following());
        assertEquals(124, TabText.width(layout.rows().get(2).text()));
        assertEquals(TabText.width(layout.rows().get(2).text()), TabText.width(layout.rows().get(3).text()));
        t.cancel();
        var cancelled = BracketLayout.render(t, Map.of(), online(t), roster(t), BracketLayout.View.following());
        assertEquals("#1 Cancelled", PLAIN.serialize(cancelled.rows().get(1).text()));
    }
    @Test void tiedStandingsKeepSharedRanks() {
        var t = tournament(4);
        var layout = BracketLayout.render(t, Map.of(), online(t), roster(t), new BracketLayout.View(0, 0, true));
        for (int i = 1; i <= 4; i++) assertTrue(PLAIN.serialize(layout.rows().get(i).text()).startsWith("1. "));
        assertTrue(layout.standings());
    }
}
