package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import net.kyori.adventure.text.Component;
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
 * Big vote countdowns. There can be several (say, one on the voting room wall and one for the judges). A timer
 * placed while looking at a wall hangs flat on it; otherwise it floats and turns to face whoever looks at it.
 * Positions are saved; the displays only exist while a vote runs.
 */
final class VoteTimerDisplay {
    /** How far away a wall can be when placing a timer on it. */
    private static final double WALL_REACH = 8;
    private static final float SCALE = 2.6f;
    /** Roughly half the height of the three lines at full scale, so a wall timer is centered where you looked. */
    private static final double HALF_HEIGHT = 0.95;
    private record Spot(Location location, boolean wall) { }

    private final Contender plugin;
    private final List<TextDisplay> displays = new ArrayList<>();
    private final Set<Chunk> tickets = new HashSet<>();

    VoteTimerDisplay(Contender plugin) {
        this.plugin = plugin;
        migrate();
    }

    /** Adds a timer where the player is looking. Returns true when it was hung on a wall. */
    boolean place(Player player) {
        if (plugin.getArenas().isArenaWorld(player.getWorld())) throw new IllegalArgumentException("Place the timer outside the arena world.");
        RayTraceResult hit = player.rayTraceBlocks(WALL_REACH, FluidCollisionMode.NEVER);
        BlockFace face = hit == null ? null : hit.getHitBlockFace();
        Spot spot;
        if (face != null && face.getModY() == 0) {
            Vector normal = face.getDirection();
            Location location = hit.getHitPosition().toLocation(player.getWorld()).add(normal.clone().multiply(0.06)).subtract(0, HALF_HEIGHT, 0);
            location.setDirection(normal);
            spot = new Spot(location, true);
        } else {
            spot = new Spot(player.getEyeLocation().add(player.getLocation().getDirection().multiply(4)).add(0, 1, 0), false);
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
        Theme theme = plugin.getThemes().current();
        Component text = Component.text("Vote", theme.primary()).appendNewline().append(Component.text("1:00", theme.secondary()))
                .appendNewline().append(Component.text("Preview", theme.muted()));
        TextDisplay display = Holograms.text(spot.location(), text, 0.01f, spot.wall() ? Display.Billboard.FIXED : Display.Billboard.CENTER,
                theme.background(plugin.getThemes().opacity()), "vote_timer");
        lit(display);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(display, Holograms.scaled(SCALE), 8), 2L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(display, Holograms.scaled(0.01f), 6), 94L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.remove(display), 100L);
    }

    /** Removes the timer closest to the player, within 12 blocks. Returns false when there is none. */
    boolean removeNearest(Player player) {
        List<Spot> spots = new ArrayList<>(spots());
        Spot nearest = null;
        double best = 12 * 12;
        for (Spot spot : spots) {
            if (!spot.location().getWorld().equals(player.getWorld())) continue;
            double distance = spot.location().distanceSquared(player.getLocation());
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

    void update(int seconds, int voted, int voters) {
        if (seconds < 0) { hide(); return; }
        Theme theme = plugin.getThemes().current();
        Component text = Component.text("Vote", theme.primary()).appendNewline()
                .append(Component.text(String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), seconds <= 10 ? DialogPalette.DANGER : theme.secondary()))
                .appendNewline().append(Component.text(voted + " of " + voters + " voted", theme.muted()));
        if (displays.isEmpty() || displays.stream().anyMatch(display -> !display.isValid())) {
            hide();
            for (Spot spot : spots()) {
                Location location = spot.location();
                if (location.getWorld() == null) continue;
                Chunk chunk = location.getChunk();
                if (!tickets.contains(chunk) && chunk.addPluginChunkTicket(plugin)) tickets.add(chunk);
                TextDisplay display = Holograms.text(location, text, 0.01f, spot.wall() ? Display.Billboard.FIXED : Display.Billboard.CENTER,
                        theme.background(plugin.getThemes().opacity()), "vote_timer");
                lit(display);
                displays.add(display);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(display, Holograms.scaled(SCALE), 8), 2L);
            }
        } else {
            for (TextDisplay display : displays) display.text(text);
        }
    }

    /** Full brightness, like a lit sign, so a timer reads clearly against a wall or in a dark room. */
    private static void lit(TextDisplay display) { display.setBrightness(new Display.Brightness(15, 15)); }

    void hide() {
        displays.forEach(Holograms::remove);
        displays.clear();
        tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
        tickets.clear();
    }

    private List<Spot> spots() {
        List<Spot> spots = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("vote-timers")) {
            World world = Bukkit.getWorld(String.valueOf(entry.get("world")));
            if (world == null) continue;
            Location location = new Location(world, number(entry, "x"), number(entry, "y"), number(entry, "z"), (float) number(entry, "yaw"), 0);
            spots.add(new Spot(location, Boolean.TRUE.equals(entry.get("wall"))));
        }
        return spots;
    }

    private static double number(Map<?, ?> entry, String key) {
        return entry.get(key) instanceof Number value ? value.doubleValue() : 0;
    }

    private void save(List<Spot> spots) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Spot spot : spots) {
            Location l = spot.location();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("world", l.getWorld().getName());
            entry.put("x", l.getX());
            entry.put("y", l.getY());
            entry.put("z", l.getZ());
            entry.put("yaw", (double) l.getYaw());
            entry.put("wall", spot.wall());
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
        List<Map<?, ?>> list = new ArrayList<>(config.getMapList("vote-timers"));
        list.add(entry);
        config.set("vote-timers", list);
        config.set("vote-timer", null);
        plugin.saveConfig();
    }
}
