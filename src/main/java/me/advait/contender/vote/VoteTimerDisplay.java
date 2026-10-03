package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Vote countdowns: just the time left, 00:00. There can be several (say, one on the voting room wall and one for
 * the judges). A timer placed while looking at a wall hangs flat on it; otherwise it floats and turns to face
 * whoever looks at it. Once voting closes, each timer turns into the results: a head and a vote count per player
 * who got votes. Positions are saved as the center of the display; the displays only exist during votes.
 */
final class VoteTimerDisplay {
    /** How far away a wall can be when placing a timer on it. */
    private static final double WALL_REACH = 8;
    /** Timer sizes run from 1 to 10; results are drawn smaller than their timer. */
    static final float DEFAULT_SIZE = 4f, MIN_SIZE = 1f, MAX_SIZE = 10f;
    /** A text display line is about a quarter of a block tall at scale 1. */
    private static final double LINE_HEIGHT = 0.25;
    private record Spot(Location center, boolean wall, float size) { }

    private final Contender plugin;
    private final List<TextDisplay> displays = new ArrayList<>();
    private final List<Spot> shown = new ArrayList<>();
    private final Set<Chunk> tickets = new HashSet<>();

    VoteTimerDisplay(Contender plugin) {
        this.plugin = plugin;
        migrate();
    }

    static String clock(int seconds) { return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }

    /** Adds a timer of this size where the player is looking. Returns true when it was hung on a wall. */
    boolean place(Player player, float size) {
        if (size < MIN_SIZE || size > MAX_SIZE) throw new IllegalArgumentException("Choose a size from 1 to 10.");
        if (plugin.getArenas().isArenaWorld(player.getWorld())) throw new IllegalArgumentException("Place the timer outside the arena world.");
        RayTraceResult hit = player.rayTraceBlocks(WALL_REACH, FluidCollisionMode.NEVER);
        BlockFace face = hit == null ? null : hit.getHitBlockFace();
        Spot spot;
        if (face != null && face.getModY() == 0) {
            Vector normal = face.getDirection();
            Location center = hit.getHitPosition().toLocation(player.getWorld()).add(normal.clone().multiply(0.06));
            center.setDirection(normal);
            spot = new Spot(center, true, size);
        } else {
            spot = new Spot(player.getEyeLocation().add(player.getLocation().getDirection().multiply(4)).add(0, 1, 0), false, size);
        }
        List<Spot> spots = new ArrayList<>(spots());
        spots.add(spot);
        save(spots);
        boolean voting = !displays.isEmpty();
        hide(); // Redrawn on the next second of a running vote, including the new one.
        if (!voting) preview(spot);
        return spot.wall();
    }

    /** Shows a new timer for a few seconds so the director can see where it went. */
    private void preview(Spot spot) {
        TextDisplay display = spawn(spot, timerText(60), 1, spot.size());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(display, Holograms.scaled(0.01f), 6), 94L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.remove(display), 100L);
    }

    /** Removes the timer closest to the player, within 12 blocks. Returns false when there is none. */
    boolean removeNearest(Player player) {
        List<Spot> spots = new ArrayList<>(spots());
        Spot nearest = null;
        double best = 12 * 12;
        for (Spot spot : spots) {
            if (!spot.center().getWorld().equals(player.getWorld())) continue;
            double distance = spot.center().distanceSquared(player.getLocation());
            if (distance < best) { best = distance; nearest = spot; }
        }
        if (nearest == null) return false;
        spots.remove(nearest);
        save(spots);
        hide();
        return true;
    }

    void removeAll() {
        hide();
        save(List.of());
    }

    int count() { return spots().size(); }

    boolean placed() { return count() > 0; }

    private Component timerText(int seconds) {
        Theme theme = plugin.getThemes().current();
        return Component.text(clock(seconds), seconds <= 10 ? DialogPalette.DANGER : theme.primary());
    }

    void update(int seconds) {
        if (seconds < 0) { hide(); return; }
        Component text = timerText(seconds);
        if (displays.isEmpty() || displays.stream().anyMatch(display -> !display.isValid())) {
            hide();
            for (Spot spot : spots()) {
                if (spot.center().getWorld() == null) continue;
                displays.add(spawn(spot, text, 1, spot.size()));
                shown.add(spot);
            }
        } else {
            for (TextDisplay display : displays) display.text(text);
        }
    }

    /**
     * Replaces each timer with the results: a head and a vote count for each player who got votes, most first.
     * More names mean smaller text, so the list keeps roughly the timer's footprint.
     */
    void showResults(List<VoteSession.Tally> results, int skips) {
        Theme theme = plugin.getThemes().current();
        List<Component> lines = new ArrayList<>();
        boolean skipShown = skips <= 0;
        for (VoteSession.Tally tally : results) {
            if (tally.votes() <= 0) continue;
            // Skip goes above anyone it ties with, since a tie with Skip saves everyone.
            if (!skipShown && skips >= tally.votes()) { lines.add(skipLine(theme, skips)); skipShown = true; }
            lines.add(Component.textOfChildren(StringUtil.getPlayerHead(tally.candidate().id()),
                    Component.text(" – " + tally.votes(), theme.primary())));
        }
        if (!skipShown) lines.add(skipLine(theme, skips));
        if (lines.isEmpty()) lines.add(Component.text("No votes have been cast", theme.muted()));
        Component text = Component.join(JoinConfiguration.newlines(), lines);
        float shrink = lines.size() <= 3 ? 0.7f : lines.size() <= 6 ? 0.55f : 0.42f;
        for (int i = 0; i < displays.size(); i++) {
            TextDisplay display = displays.get(i);
            if (!display.isValid()) continue;
            float scale = shown.get(i).size() * shrink;
            display.text(text);
            display.teleport(origin(shown.get(i), lines.size(), scale));
            Holograms.animate(display, Holograms.scaled(scale), 8);
        }
    }

    private static Component skipLine(Theme theme, int skips) {
        return Component.text("Skip", theme.muted()).append(Component.text(" – " + skips, theme.primary()));
    }

    /** Where a display of this many lines goes so it stays centered on the spot. */
    private static Location origin(Spot spot, int lines, float scale) {
        return spot.center().clone().subtract(0, lines * LINE_HEIGHT * scale / 2, 0);
    }

    private TextDisplay spawn(Spot spot, Component text, int lines, float scale) {
        Theme theme = plugin.getThemes().current();
        Location location = origin(spot, lines, scale);
        Chunk chunk = location.getChunk();
        if (!tickets.contains(chunk) && chunk.addPluginChunkTicket(plugin)) tickets.add(chunk);
        TextDisplay display = Holograms.text(location, text, 0.01f, spot.wall() ? Display.Billboard.FIXED : Display.Billboard.CENTER,
                theme.background(plugin.getThemes().opacity()), "vote_timer");
        // Full brightness, like a lit sign, so it reads clearly against a wall or in a dark room.
        display.setBrightness(new Display.Brightness(15, 15));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(display, Holograms.scaled(scale), 8), 2L);
        return display;
    }

    void hide() {
        displays.forEach(Holograms::remove);
        displays.clear();
        shown.clear();
        tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
        tickets.clear();
    }

    private List<Spot> spots() {
        List<Spot> spots = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("vote-timers")) {
            World world = Bukkit.getWorld(String.valueOf(entry.get("world")));
            if (world == null) continue;
            boolean wall = Boolean.TRUE.equals(entry.get("wall"));
            Location center = new Location(world, number(entry, "x"), number(entry, "y"), number(entry, "z"), (float) number(entry, "yaw"), 0);
            // Wall timers saved before positions were centers stored their bottom edge, about 0.95 below the center.
            if (wall && !Boolean.TRUE.equals(entry.get("centered"))) center.add(0, 0.95, 0);
            float size = entry.get("size") instanceof Number value ? value.floatValue() : DEFAULT_SIZE;
            spots.add(new Spot(center, wall, Math.clamp(size, MIN_SIZE, MAX_SIZE)));
        }
        return spots;
    }

    private static double number(Map<?, ?> entry, String key) {
        return entry.get(key) instanceof Number value ? value.doubleValue() : 0;
    }

    private void save(List<Spot> spots) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Spot spot : spots) {
            Location l = spot.center();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("world", l.getWorld().getName());
            entry.put("x", l.getX());
            entry.put("y", l.getY());
            entry.put("z", l.getZ());
            entry.put("yaw", (double) l.getYaw());
            entry.put("wall", spot.wall());
            entry.put("size", (double) spot.size());
            entry.put("centered", true);
            list.add(entry);
        }
        plugin.getConfig().set("vote-timers", list);
        plugin.saveConfig();
    }

    /** Servers set up before timers could be on walls saved one floating timer under vote-timer. */
    private void migrate() {
        var config = plugin.getConfig();
        String name = config.getString("vote-timer.world");
        if (name == null) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("world", name);
        entry.put("x", config.getDouble("vote-timer.x"));
        entry.put("y", config.getDouble("vote-timer.y"));
        entry.put("z", config.getDouble("vote-timer.z"));
        entry.put("yaw", 0.0);
        entry.put("wall", false);
        entry.put("centered", true);
        List<Map<?, ?>> list = new ArrayList<>(config.getMapList("vote-timers"));
        list.add(entry);
        config.set("vote-timers", list);
        config.set("vote-timer", null);
        plugin.saveConfig();
    }
}
