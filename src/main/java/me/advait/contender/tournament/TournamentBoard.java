package me.advait.contender.tournament;

import me.advait.contender.Contender;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.Dialogs;
import me.advait.contender.duel.Duel;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.tab.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.*;

/** The tab bracket placed in the world, with one private highlight per viewer. */
public final class TournamentBoard extends AbstractGameState {
    private static final float SCALE = 1.0f;
    private static final double ROW_HEIGHT = 0.30;
    private final Contender contender;
    private final List<Cell> cells = new ArrayList<>();
    private final Map<UUID, Hover> hovers = new HashMap<>();
    private final Map<UUID, Long> clicks = new HashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private Location anchor;
    private Vector normal, right;
    private BracketLayout.View view = BracketLayout.View.following();
    private BracketLayout.Layout layout;
    private UUID tournamentId;
    private int columns, columnPixels;
    private int backgroundOpacity = -1;
    private static final class Cell {
        final TextDisplay display;
        final double x, y, width;
        Component text;
        Integer target;
        Cell(TextDisplay display, double x, double y, double width) { this.display = display; this.x = x; this.y = y; this.width = width; }
    }
    private record Hover(Player player, Cell cell, TextDisplay display) { }
    public TournamentBoard(Contender plugin) { super(plugin); contender = plugin; }
    @Override protected void onEnable() { load(); runRepeating(this::hover, 1, 2); }
    @Override protected void onDisable() { close(); }
    public void load() {
        String name = contender.getConfig().getString("tournament-board.world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) return;
        setAnchor(new Location(world, contender.getConfig().getDouble("tournament-board.x"),
                contender.getConfig().getDouble("tournament-board.y"), contender.getConfig().getDouble("tournament-board.z"),
                (float) contender.getConfig().getDouble("tournament-board.yaw", 180), 0));
    }
    public void place(Player player) {
        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 0.001) direction.setZ(1);
        Location location = player.getLocation().add(direction.normalize().multiply(10)).add(0, 7, 0);
        location.setYaw(player.getLocation().getYaw() + 180); location.setPitch(0);
        if (contender.getArenaManager().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Place the board outside the arena pool.");
        setAnchor(location);
        contender.getConfig().set("tournament-board.world", location.getWorld().getName());
        contender.getConfig().set("tournament-board.x", location.getX()); contender.getConfig().set("tournament-board.y", location.getY());
        contender.getConfig().set("tournament-board.z", location.getZ()); contender.getConfig().set("tournament-board.yaw", location.getYaw());
        contender.saveConfig();
    }
    private void setAnchor(Location location) {
        close(); anchor = location.clone();
        normal = anchor.getDirection();
        double yaw = Math.toRadians(anchor.getYaw());
        right = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
    }
    public static int backgroundOpacity(org.bukkit.configuration.ConfigurationSection config) {
        return Math.clamp(config.getInt("tournament-board.background-opacity", 0), 0, 100);
    }
    private Color backgroundColor() {
        return Color.fromARGB(Math.round(backgroundOpacity(contender.getConfig()) * 255f / 100f), 16, 22, 29);
    }
    public void update(Tournament tournament, Map<Integer, Duel> playing) {
        if (anchor == null || contender.getTabManager() == null) return;
        var event = contender.getTabManager().event();
        UUID id = event == null ? null : event.id();
        if (!Objects.equals(tournamentId, id)) { view = BracketLayout.View.following(); tournamentId = id; clearHovers(); }
        if (event != null && event.cancelled()) { destroyDisplays(); layout = null; return; }
        layout = contender.getTabManager().layout(view);
        List<TabRow> rows = layout == null ? List.of(TabRow.label(Component.text("Create a tournament with /tournament.", NamedTextColor.GRAY))) : layout.rows();
        int newColumns = Math.max(1, (rows.size() + 19) / 20);
        int pixels = Math.max(170, rows.stream().mapToInt(row -> TabText.width(row.boardText())).max().orElse(170) + 12);
        if (columns != newColumns || columnPixels != pixels || cells.isEmpty()) {
            destroyDisplays(); columns = newColumns; columnPixels = pixels;
            double width = pixels / 40.0 * SCALE, gap = 0.2;
            for (int column = 0; column < columns; column++) for (int row = 0; row < 20; row++) {
                double x = (column - (columns - 1) / 2.0) * (width + gap);
                cells.add(cell(x, -row * ROW_HEIGHT, width));
            }
            cells.add(cell(0, 0.75, (width + gap) * columns)); // Header
            cells.add(cell(0, -20 * ROW_HEIGHT - 0.2, (width + gap) * columns)); // Caption
            // Controls use two rows, with paging on its own row.
            for (int row = 0; row < 3; row++) for (int side = 0; side < 2; side++) cells.add(cell((side == 0 ? -1 : 1) * 2.1,
                    -20 * ROW_HEIGHT - 0.7 - row * 0.4, 4));
        }
        int opacity = backgroundOpacity(contender.getConfig());
        if (backgroundOpacity != opacity) {
            backgroundOpacity = opacity;
            for (Cell cell : cells) cell.display.setBackgroundColor(backgroundColor());
            for (Hover hover : hovers.values()) hover.display().setBackgroundColor(backgroundColor());
        }
        for (int i = 0; i < columns * 20; i++) {
            TabRow row = i < rows.size() ? rows.get(i) : TabRow.label(Component.empty());
            Integer target = event != null && !event.minigame() && tournament != null && !tournament.isCancelled() && row.matchNumber() != null && playing.containsKey(row.matchNumber()) ? row.matchNumber() : null;
            set(cells.get(i), row.boardText().append(TabText.padding(columnPixels - 12 - TabText.width(row.boardText()))), target);
        }
        int offset = columns * 20;
        set(cells.get(offset++), TabStyle.read(contender.getConfig()).header(event == null ? null : event.name()), null);
        Component caption = layout == null ? Component.empty() : Component.text(event != null && event.minigame() ? event.caption() + " | " + event.status() : layout.heading(), NamedTextColor.AQUA);
        set(cells.get(offset++), caption, null);
        if (event != null && event.minigame()) {
            while (offset < cells.size()) set(cells.get(offset++), Component.empty(), null);
            hover(); return;
        }
        set(cells.get(offset++), DialogIcon.BACK.label("Previous round"), layout != null && layout.round() > 1 ? -3 : null);
        set(cells.get(offset++), DialogIcon.NEXT.label("Next round"), layout != null && layout.round() < BracketLayout.rounds(tournament) ? -4 : null);
        set(cells.get(offset++), DialogIcon.BOARD.label(layout != null && layout.standings() ? "Matches" : "Standings"), layout != null ? -5 : null);
        set(cells.get(offset++), DialogIcon.REFRESH.label("Follow current round"), layout != null ? -6 : null);
        set(cells.get(offset++), layout != null && layout.pages() > 1 ? DialogIcon.BACK.label("Previous page") : Component.empty(), layout != null && layout.pages() > 1 ? -1 : null);
        set(cells.get(offset), layout != null && layout.pages() > 1 ? DialogIcon.NEXT.label("Next page") : Component.empty(), layout != null && layout.pages() > 1 ? -2 : null);
        hover();
    }
    private Cell cell(double x, double y, double width) {
        Location location = anchor.clone().add(right.clone().multiply(x)).add(0, y, 0);
        return new Cell(spawn(location, false), x, y + ROW_HEIGHT / 2, width);
    }
    private TextDisplay spawn(Location location, boolean privateDisplay) {
        Chunk chunk = location.getChunk();
        if (!tickets.contains(chunk) && chunk.addPluginChunkTicket(plugin)) tickets.add(chunk);
        return location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false); entity.setVisibleByDefault(!privateDisplay);
            entity.setBillboard(Display.Billboard.FIXED); entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            entity.setLineWidth(16384); entity.setViewRange(1.5f); entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(backgroundColor());
            entity.setTransformationMatrix(new Matrix4f().scaling(SCALE));
            entity.setInterpolationDuration(2);
        });
    }
    private void set(Cell cell, Component text, Integer target) {
        cell.target = target;
        if (text.equals(cell.text)) return;
        cell.text = text;
        cell.display.text(text);
        for (Hover hover : hovers.values()) if (hover.cell() == cell) hover.display().text(text);
    }
    private Cell selected(Player player) {
        if (anchor == null || !player.getWorld().equals(anchor.getWorld())) return null;
        Location eye = player.getEyeLocation();
        BoardHitbox.Hit hit = BoardHitbox.intersect(eye.toVector(), eye.getDirection(), anchor.toVector(), normal, right, 24);
        if (hit == null) return null;
        var obstruction = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), hit.distance(), FluidCollisionMode.NEVER, true);
        if (obstruction != null) return null;
        for (Cell cell : cells) if (cell.target != null && BoardHitbox.contains(hit, cell.x, cell.y, cell.width, ROW_HEIGHT)) return cell;
        return null;
    }
    private void hover() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Cell selected = selected(player);
            Hover previous = hovers.get(player.getUniqueId());
            if (previous != null && previous.cell() == selected) continue;
            clearHover(player.getUniqueId());
            if (selected == null) continue;
            Location location = selected.display.getLocation().add(normal.clone().multiply(0.04));
            TextDisplay display = spawn(location, true);
            display.text(selected.text); display.setBackgroundColor(backgroundColor());
            player.showEntity(plugin, display); player.hideEntity(plugin, selected.display);
            display.setInterpolationDelay(0);
            display.setTransformationMatrix(new Matrix4f().scaling(SCALE * 1.08f));
            hovers.put(player.getUniqueId(), new Hover(player, selected, display));
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void click(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer(); Cell cell = selected(player);
        if (cell == null) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - clicks.getOrDefault(player.getUniqueId(), 0L) < 300) return;
        clicks.put(player.getUniqueId(), now);
        try {
            var presentation = contender.getTabManager().event();
            if (presentation == null || presentation.cancelled() || presentation.minigame()) return;
            Tournament current = contender.getTournamentManager().current();
            if (current == null || !current.id().equals(tournamentId)) return;
            int target = cell.target;
            if (target > 0) {
                Duel duel = contender.getTournamentManager().playing().get(target);
                if (duel == null) throw new IllegalStateException("That match has ended.");
                contender.getDuelManager().spectate(player, duel);
                clearHover(player.getUniqueId());
            } else if (layout != null) {
                view = switch (target) {
                    case -1 -> new BracketLayout.View(view.round(), layout.page() - 1, view.standings());
                    case -2 -> new BracketLayout.View(view.round(), layout.page() + 1, view.standings());
                    case -3 -> new BracketLayout.View(layout.round() - 1, 0, false);
                    case -4 -> new BracketLayout.View(layout.round() + 1, 0, false);
                    case -5 -> new BracketLayout.View(view.round(), 0, !view.standings());
                    default -> BracketLayout.View.following();
                };
                update(current, contender.getTournamentManager().playing());
            }
        } catch (RuntimeException failure) { Dialogs.error(player, Dialogs.message(failure)); }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { clearHover(event.getPlayer().getUniqueId()); clicks.remove(event.getPlayer().getUniqueId()); }
    private void clearHover(UUID id) {
        Hover hover = hovers.remove(id);
        if (hover == null) return;
        hover.display().remove();
        if (hover.player().isOnline()) hover.player().showEntity(plugin, hover.cell().display);
    }
    private void clearHovers() { for (UUID id : Set.copyOf(hovers.keySet())) clearHover(id); }
    private void destroyDisplays() {
        clearHovers(); for (Cell cell : cells) cell.display.remove(); cells.clear();
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin); tickets.clear();
    }
    public void remove() {
        close();
        for (String key : List.of("world", "x", "y", "z", "yaw")) contender.getConfig().set("tournament-board." + key, null);
        contender.saveConfig();
    }
    public void close() { destroyDisplays(); anchor = null; layout = null; columns = 0; clicks.clear(); }
}
