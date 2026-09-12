package me.advait.contender.tab;

import me.advait.contender.tournament.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;
import java.util.function.Function;

/** Column-major, 20 rows per column. Match blocks never cross a column or page. */
public final class BracketLayout {
    public record Presence(String name, Component display, int latency, String texture, String signature, Component head) {
        public Presence(String name, Component display, int latency, String texture, String signature) {
            this(name, display, latency, texture, signature, Component.empty());
        }
    }
    public record Score(int first, int second) { }
    public record View(int round, int page, boolean standings) {
        public static View following() { return new View(0, 0, false); }
    }
    public record Layout(List<TabRow> rows, int round, int page, int pages, boolean standings) {
        public Layout { rows = List.copyOf(rows); }
        public String heading() { return standings ? "Standings" : "Round " + round; }
        public String caption() { return heading() + " | Page " + (page + 1) + "/" + pages; }
    }
    private static final int MATCHES_PER_COLUMN = 4;
    private static final int MATCHES_PER_PAGE = 12;
    private static final int ROSTER_PER_PAGE = 19;
    private BracketLayout() { }

    public static int currentRound(Tournament tournament) {
        return tournament.matches().stream().filter(m -> m.status() != TournamentMatch.Status.FINISHED)
                .mapToInt(TournamentMatch::round).min().orElseGet(() -> rounds(tournament));
    }
    public static int rounds(Tournament tournament) {
        return tournament.matches().stream().mapToInt(TournamentMatch::round).max().orElse(1);
    }
    public static Layout render(Tournament tournament, Map<Integer, Score> liveScores, Function<UUID, Presence> presence,
                                List<UUID> roster, View view) {
        int round = view.round() == 0 ? currentRound(tournament) : Math.clamp(view.round(), 1, rounds(tournament));
        List<TournamentMatch> matches = tournament.matches().stream().filter(m -> m.round() == round).toList();
        int contentPages = view.standings() ? pages(tournament.entries().size(), 57) : pages(matches.size(), MATCHES_PER_PAGE);
        int rosterPages = pages(roster.size(), ROSTER_PER_PAGE);
        int pageCount = Math.max(contentPages, rosterPages);
        int page = Math.floorMod(view.page(), pageCount);
        List<TabRow> rows = new ArrayList<>();
        if (view.standings()) standings(rows, tournament, presence, page % contentPages);
        else matches(rows, tournament, matches, liveScores, presence, round, page % contentPages);
        int start = (page % rosterPages) * ROSTER_PER_PAGE;
        rows.add(label("Players (" + roster.size() + ")", NamedTextColor.GOLD));
        for (int i = start; i < Math.min(start + ROSTER_PER_PAGE, roster.size()); i++) {
            Presence player = presence.apply(roster.get(i));
            rows.add(new TabRow(player.display(), player.latency(), player.texture(), player.signature(), withHead(player, player.display()), null));
        }
        pad(rows);
        return new Layout(rows, round, page, pageCount, view.standings());
    }
    private static void matches(List<TabRow> rows, Tournament tournament, List<TournamentMatch> matches,
                                Map<Integer, Score> liveScores, Function<UUID, Presence> presence, int round, int page) {
        int scoreWidth = scoreWidth(tournament, presence);
        int start = page * MATCHES_PER_PAGE;
        int end = Math.min(start + MATCHES_PER_PAGE, matches.size());
        int columns = Math.max(1, (end - start + MATCHES_PER_COLUMN - 1) / MATCHES_PER_COLUMN);
        for (int column = 0; column < columns; column++) {
            rows.add(label("Round " + round, NamedTextColor.GOLD));
            for (int i = start + column * MATCHES_PER_COLUMN; i < Math.min(start + (column + 1) * MATCHES_PER_COLUMN, end); i++) {
                TournamentMatch match = matches.get(i);
                boolean cancelled = tournament.isCancelled() && match.status() != TournamentMatch.Status.FINISHED;
                String status = cancelled ? "Cancelled" : switch (match.status()) {
                    case WAITING -> "Waiting";
                    case PLAYING -> "Playing";
                    case FINISHED -> match.result().reason() == me.advait.contender.duel.DuelResult.Reason.FORFEIT ? "Forfeit" : "Finished";
                };
                NamedTextColor statusColor = cancelled ? NamedTextColor.RED : switch (match.status()) {
                    case WAITING -> NamedTextColor.YELLOW; case PLAYING -> NamedTextColor.GREEN; case FINISHED -> NamedTextColor.AQUA;
                };
                rows.add(TabRow.label(Component.text("#" + match.number() + " ", NamedTextColor.GRAY)
                        .append(Component.text(status, statusColor))).match(match.number()));
                Score score = match.result() != null ? new Score(match.result().team1Score(), match.result().team2Score())
                        : liveScores.getOrDefault(match.number(), new Score(0, 0));
                rows.add(side(tournament.entries().get(match.first()), score.first(), match, 1, tournament.teams(), presence, scoreWidth));
                rows.add(side(tournament.entries().get(match.second()), score.second(), match, 2, tournament.teams(), presence, scoreWidth));
                rows.add(label("----------------------", NamedTextColor.DARK_AQUA));
            }
            pad(rows);
        }
    }
    private static Component withHead(Presence player, Component text) {
        return Component.textOfChildren(player.head(), Component.space(), text);
    }
    private static Component entryName(TournamentEntry entry, boolean teams, Function<UUID, Presence> presence) {
        if (!teams) return presence.apply(entry.players().getFirst()).display();
        Component name = Component.text(entry.name() + ": ", NamedTextColor.GRAY);
        for (int i = 0; i < entry.players().size(); i++) {
            if (i > 0) name = name.append(Component.text(" / ", NamedTextColor.DARK_GRAY));
            Presence player = presence.apply(entry.players().get(i));
            name = name.append(withHead(player, player.display()));
        }
        return name;
    }
    private static int scoreWidth(Tournament tournament, Function<UUID, Presence> presence) {
        return Math.max(124, tournament.entries().stream().mapToInt(entry -> TabText.width(entryName(entry, tournament.teams(), presence)) + 16).max().orElse(124));
    }
    private static TabRow side(TournamentEntry entry, int score, TournamentMatch match, int side, boolean teams,
                               Function<UUID, Presence> presence, int width) {
        List<Presence> players = entry.players().stream().map(presence).toList();
        NamedTextColor color = match.result() != null && Objects.equals(match.result().winner(), side) ? NamedTextColor.GREEN : NamedTextColor.GOLD;
        Component text = TabText.scored(entryName(entry, teams, presence), Integer.toString(score), color, width);
        return new TabRow(text, latency(players),
                !teams ? players.getFirst().texture() : TabSkin.SPACER, !teams ? players.getFirst().signature() : TabSkin.SIGNATURE,
                !teams ? withHead(players.getFirst(), text) : text, match.number());
    }
    private static void standings(List<TabRow> rows, Tournament tournament, Function<UUID, Presence> presence, int page) {
        var standings = tournament.standings();
        int width = scoreWidth(tournament, presence) + 24;
        int start = page * 57, end = Math.min(start + 57, standings.size());
        int columns = Math.max(1, (end - start + 18) / 19);
        for (int column = 0; column < columns; column++) {
            rows.add(label("Standings             Pts", NamedTextColor.GOLD));
            for (int i = start + column * 19; i < Math.min(start + (column + 1) * 19, end); i++) {
                Tournament.Standing standing = standings.get(i);
                int rank = i + 1;
                while (rank > 1 && standings.get(rank - 2).points() == standing.points()
                        && standings.get(rank - 2).roundDifference() == standing.roundDifference()) rank--;
                List<Presence> players = standing.entry().players().stream().map(presence).toList();
                Component name = Component.text(rank + ". ", NamedTextColor.GRAY)
                        .append(entryName(standing.entry(), tournament.teams(), presence));
                Component text = TabText.scored(name, Integer.toString(standing.points()), NamedTextColor.GOLD, width);
                rows.add(new TabRow(text, latency(players), !tournament.teams() ? players.getFirst().texture() : TabSkin.SPACER,
                        !tournament.teams() ? players.getFirst().signature() : TabSkin.SIGNATURE,
                        !tournament.teams() ? withHead(players.getFirst(), text) : text, null));
            }
            pad(rows);
        }
    }
    static int latency(List<Presence> players) {
        return players.stream().anyMatch(p -> p.latency() < 0) ? -1 : players.stream().mapToInt(Presence::latency).max().orElse(-1);
    }
    public static Component scored(String name, String score, NamedTextColor color) {
        return TabText.scored(name, score, color);
    }
    private static int pages(int count, int size) { return Math.max(1, (count + size - 1) / size); }
    private static TabRow label(String text, NamedTextColor color) { return TabRow.label(Component.text(text, color)); }
    private static void pad(List<TabRow> rows) { while (rows.size() % 20 != 0) rows.add(TabRow.label(Component.empty())); }
}
