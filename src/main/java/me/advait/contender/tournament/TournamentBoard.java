package me.advait.contender.tournament;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.List;
import java.util.Map;

public final class TournamentBoard {
    private final Contender plugin;
    private TextDisplay display;
    private Chunk displayChunk;
    private Component lastText;
    private int ticks;

    public TournamentBoard(Contender plugin) { this.plugin = plugin; }
    public void load() {
        String name = plugin.getConfig().getString("tournament-board.world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) return;
        spawn(new Location(world, plugin.getConfig().getDouble("tournament-board.x"),
                plugin.getConfig().getDouble("tournament-board.y"), plugin.getConfig().getDouble("tournament-board.z")));
    }
    public void place(Player player) {
        var direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 0.001) direction.setZ(1);
        Location location = player.getLocation().add(direction.normalize().multiply(4)).add(0, 3, 0);
        if (plugin.getArenaManager().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Place the board in the lobby or another world outside the arena pool.");
        spawn(location);
        plugin.getConfig().set("tournament-board.world", location.getWorld().getName());
        plugin.getConfig().set("tournament-board.x", location.getX());
        plugin.getConfig().set("tournament-board.y", location.getY());
        plugin.getConfig().set("tournament-board.z", location.getZ());
        plugin.saveConfig();
    }
    private void spawn(Location location) {
        close();
        Chunk chunk = location.getChunk();
        if (chunk.addPluginChunkTicket(plugin)) displayChunk = chunk;
        display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            entity.setLineWidth(700);
            entity.setViewRange(2f);
            entity.setShadowed(true);
            entity.setBackgroundColor(Color.fromARGB(190, 16, 18, 20));
            entity.text(Component.text("No tournament yet. Use /tournament to create one."));
        });
    }
    public void update(Tournament tournament, Map<Integer, Duel> playing) {
        if (display == null || !display.isValid()) return;
        Component text = tournament == null ? Component.text("No tournament yet. Use /tournament to create one.")
                : Component.text(render(tournament, playing, ticks++ / 10));
        if (!text.equals(lastText)) { display.text(text); lastText = text; }
    }
    public static String render(Tournament tournament, Map<Integer, Duel> playing, int page) {
        StringBuilder text = new StringBuilder(tournament.name()).append(" | ").append(tournament.statusText()).append('\n');
        List<Tournament.Standing> standings = tournament.standings();
        int standingsPages = Math.max(1, (standings.size() + 9) / 10);
        int standingsStart = Math.floorMod(page, standingsPages) * 10;
        text.append("Standings (win 3 points, draw 1)\n");
        for (int i = standingsStart; i < Math.min(standingsStart + 10, standings.size()); i++) {
            var row = standings.get(i);
            int rank = i + 1;
            while (rank > 1 && standings.get(rank - 2).points() == row.points()
                    && standings.get(rank - 2).roundDifference() == row.roundDifference()) rank--;
            text.append(rank).append(". ").append(row.entry().name()).append("  ").append(row.points())
                    .append(" pts  ").append(row.wins()).append("W ").append(row.draws()).append("D ")
                    .append(row.losses()).append("L  ").append(String.format("%+d", row.roundDifference())).append('\n');
        }
        int pages = Math.max(1, (tournament.matches().size() + 7) / 8);
        int current = Math.floorMod(page, pages);
        text.append("\nMatches ").append(current + 1).append('/').append(pages).append('\n');
        for (int i = current * 8; i < Math.min(current * 8 + 8, tournament.matches().size()); i++) {
            TournamentMatch match = tournament.matches().get(i);
            text.append('#').append(match.number()).append(" R").append(match.round()).append("  ")
                    .append(tournament.entries().get(match.first()).name()).append(" vs ")
                    .append(tournament.entries().get(match.second()).name()).append("  ");
            Duel duel = playing.get(match.number());
            if (duel != null) text.append(duel.getTeam1().getScore()).append('-').append(duel.getTeam2().getScore()).append(" playing");
            else if (match.result() != null) {
                text.append(match.result().team1Score()).append('-').append(match.result().team2Score());
                if (match.result().reason() == me.advait.contender.duel.DuelResult.Reason.FORFEIT) {
                    int winner = match.result().winner() == 1 ? match.first() : match.second();
                    text.append(" (forfeit: ").append(tournament.entries().get(winner).name()).append(")");
                }
            } else text.append("waiting, best of ").append(match.bestOf());
            text.append('\n');
        }
        return text.toString().stripTrailing();
    }
    public void remove() {
        close();
        plugin.getConfig().set("tournament-board", null);
        plugin.saveConfig();
    }
    public void close() {
        if (display != null) display.remove();
        if (displayChunk != null) displayChunk.removePluginChunkTicket(plugin);
        displayChunk = null;
        display = null;
        lastText = null;
    }
}
