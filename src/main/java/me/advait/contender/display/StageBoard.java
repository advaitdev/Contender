package me.advait.contender.display;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.duel.Duel;
import me.advait.contender.stage.Stage;
import me.advait.contender.tab.*;
import me.advait.contender.tournament.BoardHitbox;
import me.advait.contender.util.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

/**
 * The tab bracket, drawn with text displays in the lobby. Point at a live match and swing to watch it;
 * the controls underneath switch rounds, standings and pages.
 */
public final class StageBoard extends Module {
    /** Text scale: bigger than a default display so it reads from across the lobby. */
    private static final float SCALE = 1.4f;
    private static final double ROW_HEIGHT = 0.30 * SCALE;
    private static final int ROWS = 20;
    /** Space under the last row for the caption and the three rows of controls. */
    private static final double FOOTER = 1.8 * SCALE;
    private final List<Cell> cells = new ArrayList<>();
    private final Map<UUID, Hover> hovers = new HashMap<>();
    private final Map<UUID, Long> clicks = new HashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    // The board grows upward from base (just above the floor); anchor is its current top-left reference.
    private Location base, anchor;
    private int rowsPerColumn;
    private Vector normal, right;
    private BracketLayout.View view = BracketLayout.View.following();
    private BracketLayout.Layout layout;
    private UUID stageId;
    private int columns, columnPixels;

    private static final class Cell {
        final TextDisplay display;
        final double x, y, width;
        Component text;
        Integer target;
        Cell(TextDisplay display, double x, double y, double width) { this.display = display; this.x = x; this.y = y; this.width = width; }
    }
    private record Hover(Player player, Cell cell, TextDisplay display) { }

    public StageBoard(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        load();
        plugin.getThemes().onChange(theme -> { if (isEnabled()) rebuild(); });
        tasks.repeat(20, 20, this::refresh);
        tasks.repeat(1, 2, this::hover);
    }

    @Override protected void onDisable() { close(); }

    private void load() {
        var config = plugin.getConfig();
        String name = config.getString("tournament-board.world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) return;
        double y = config.getDouble("tournament-board.y");
        // Boards placed before version 2 saved their top, seven blocks above the player's feet.
        if (config.getInt("tournament-board.version", 1) < 2) y = y - 7 + 0.3;
        setBase(new Location(world, config.getDouble("tournament-board.x"), y,
                config.getDouble("tournament-board.z"), (float) config.getDouble("tournament-board.yaw", 180), 0));
    }

    public boolean placed() { return base != null; }

    /** Places the board eight blocks in front of the player, facing them, standing on the floor. */
    public void place(Player player) {
        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 0.001) direction.setZ(1);
        Location location = player.getLocation().add(direction.normalize().multiply(8)).add(0, 0.3, 0);
        location.setYaw(player.getLocation().getYaw() + 180);
        location.setPitch(0);
        if (plugin.getArenas().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Place the board outside the arena world.");
        setBase(location);
        var config = plugin.getConfig();
        config.set("tournament-board.version", 2);
        config.set("tournament-board.world", location.getWorld().getName());
        config.set("tournament-board.x", location.getX());
        config.set("tournament-board.y", location.getY());
        config.set("tournament-board.z", location.getZ());
        config.set("tournament-board.yaw", location.getYaw());
        plugin.saveConfig();
        refresh();
    }

    public void remove() {
        close();
        base = null;
        anchor = null;
        for (String key : List.of("world", "x", "y", "z", "yaw", "version")) plugin.getConfig().set("tournament-board." + key, null);
        plugin.saveConfig();
    }

    private void setBase(Location location) {
        close();
        base = location.clone();
        anchor = base.clone();
        normal = base.getDirection();
        double yaw = Math.toRadians(base.getYaw());
        right = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
    }

    /** Redraws after a theme or opacity change. */
    public void rebuild() {
        destroyDisplays();
        columns = 0;
        refresh();
    }

    public void refresh() {
        if (!isEnabled() || base == null || base.getWorld() == null || !base.isChunkLoaded() && base.getWorld().getPlayers().isEmpty()) return;
        Stage stage = plugin.getStages().current();
        if (stage != null && stage.cancelled()) stage = null;
        UUID id = stage == null ? null : stage.id();
        if (!Objects.equals(stageId, id)) { view = BracketLayout.View.following(); stageId = id; clearHovers(); }
        Theme theme = plugin.getThemes().current();
        layout = stage == null ? null : plugin.getTabManager().layout(view);
        List<TabRow> rows = layout == null
                ? List.of(TabRow.label(Component.text("Waiting for the next event", theme.muted())))
                : layout.rows();
        int newColumns = Math.max(1, (rows.size() + ROWS - 1) / ROWS);
        // Layout columns are padded to ROWS; draw only down to the last row any column uses.
        int used = 0;
        for (int index = 0; index < rows.size(); index++) {
            if (!PlainTextComponentSerializer.plainText().serialize(rows.get(index).boardText()).isBlank()) used = Math.max(used, index % ROWS + 1);
        }
        int newRows = Math.clamp(used, 1, ROWS);
        int pixels = Math.max(170, rows.stream().mapToInt(row -> TabText.width(row.boardText())).max().orElse(170) + 12);
        if (columns != newColumns || columnPixels != pixels || rowsPerColumn != newRows || cells.isEmpty()) {
            destroyDisplays();
            columns = newColumns;
            columnPixels = pixels;
            rowsPerColumn = newRows;
            anchor = base.clone().add(0, rowsPerColumn * ROW_HEIGHT + FOOTER, 0);
            double width = pixels / 40.0 * SCALE, gap = 0.2 * SCALE;
            for (int column = 0; column < columns; column++) for (int row = 0; row < rowsPerColumn; row++) {
                double x = (column - (columns - 1) / 2.0) * (width + gap);
                cells.add(cell(x, -row * ROW_HEIGHT, width));
            }
            Cell title = cell(0, 0.95 * SCALE, (width + gap) * columns);
            title.display.setTransformationMatrix(new Matrix4f().scaling(SCALE * 1.8f));
            cells.add(title);
            cells.add(cell(0, -rowsPerColumn * ROW_HEIGHT - 0.2 * SCALE, (width + gap) * columns));
            for (int row = 0; row < 3; row++) for (int side = 0; side < 2; side++) {
                cells.add(cell((side == 0 ? -1 : 1) * 2.1 * SCALE, -rowsPerColumn * ROW_HEIGHT - (0.7 + row * 0.4) * SCALE, 4 * SCALE));
            }
        }
        Map<Integer, Duel> playing = plugin.getTournaments().playing();
        boolean bracket = stage != null && stage.hasRounds();
        for (int i = 0; i < columns * rowsPerColumn; i++) {
            int source = (i / rowsPerColumn) * ROWS + i % rowsPerColumn;
            TabRow row = source < rows.size() ? rows.get(source) : TabRow.label(Component.empty());
            Integer target = bracket && row.matchNumber() != null && playing.containsKey(row.matchNumber()) ? row.matchNumber() : null;
            set(cells.get(i), row.boardText().append(TabText.padding(columnPixels - 12 - TabText.width(row.boardText()))), target);
        }
        int offset = columns * rowsPerColumn;
        set(cells.get(offset++), Component.text(TabStyle.read(plugin.getConfig()).heading(stage == null ? null : stage.name()), theme.primary()), null);
        Component caption = stage == null ? Component.empty() : Component.text(stage.caption(layout) + "  |  " + stage.statusText(), theme.secondary());
        set(cells.get(offset++), caption, null);
        if (!bracket || layout == null) {
            while (offset < cells.size()) set(cells.get(offset++), Component.empty(), null);
            return;
        }
        set(cells.get(offset++), DialogIcon.BACK.label("Previous round"), layout.round() > 1 ? -3 : null);
        set(cells.get(offset++), DialogIcon.NEXT.label("Next round"), layout.round() < stage.rounds() ? -4 : null);
        set(cells.get(offset++), DialogIcon.BOARD.label(layout.standings() ? "Matches" : "Standings"), -5);
        set(cells.get(offset++), DialogIcon.REFRESH.label("Follow current round"), -6);
        boolean pages = layout.pages() > 1;
        set(cells.get(offset++), pages ? DialogIcon.BACK.label("Previous page") : Component.empty(), pages ? -1 : null);
        set(cells.get(offset), pages ? DialogIcon.NEXT.label("Next page") : Component.empty(), pages ? -2 : null);
    }

    private Cell cell(double x, double y, double width) {
        Location location = anchor.clone().add(right.clone().multiply(x)).add(0, y, 0);
        return new Cell(spawn(location, false), x, y + ROW_HEIGHT / 2, width);
    }

    private TextDisplay spawn(Location location, boolean privateDisplay) {
        Chunk chunk = location.getChunk();
        if (!tickets.contains(chunk) && chunk.addPluginChunkTicket(plugin)) tickets.add(chunk);
        Theme theme = plugin.getThemes().current();
        return location.getWorld().spawn(location, TextDisplay.class, entity -> {
            Tags.managed(entity, "board");
            entity.setVisibleByDefault(!privateDisplay);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            entity.setLineWidth(16384);
            entity.setViewRange(1.5f);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(theme.background(plugin.getThemes().opacity()));
            entity.setInterpolationDuration(2);
            entity.setTransformationMatrix(new Matrix4f().scaling(SCALE));
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
        if (player.getWorld().rayTraceBlocks(eye, eye.getDirection(), hit.distance(), FluidCollisionMode.NEVER, true) != null) return null;
        for (Cell cell : cells) if (cell.target != null && BoardHitbox.contains(hit, cell.x, cell.y, cell.width, ROW_HEIGHT)) return cell;
        return null;
    }

    private void hover() {
        if (anchor == null) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Cell selected = selected(player);
            Hover previous = hovers.get(player.getUniqueId());
            if (previous != null && previous.cell() == selected) continue;
            clearHover(player.getUniqueId());
            if (selected == null) continue;
            Location location = selected.display.getLocation().add(normal.clone().multiply(0.04));
            TextDisplay display = spawn(location, true);
            display.text(selected.text);
            player.showEntity(plugin, display);
            player.hideEntity(plugin, selected.display);
            display.setInterpolationDelay(0);
            display.setTransformationMatrix(new Matrix4f().scaling(SCALE * 1.08f));
            hovers.put(player.getUniqueId(), new Hover(player, selected, display));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        Cell cell = selected(player);
        if (cell == null) return;
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - clicks.getOrDefault(player.getUniqueId(), 0L) < 300) return;
        clicks.put(player.getUniqueId(), now);
        try {
            int target = cell.target;
            if (target > 0) {
                Duel duel = plugin.getTournaments().playing().get(target);
                if (duel == null) throw new IllegalStateException("That match has ended.");
                plugin.getSpectate().watch(player, duel);
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
                refresh();
            }
        } catch (RuntimeException failure) { Msg.error(player, Msg.reason(failure)); }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        clearHover(event.getPlayer().getUniqueId());
        clicks.remove(event.getPlayer().getUniqueId());
    }

    private void clearHover(UUID id) {
        Hover hover = hovers.remove(id);
        if (hover == null) return;
        hover.display().remove();
        if (hover.player().isOnline() && hover.cell().display.isValid()) hover.player().showEntity(plugin, hover.cell().display);
    }

    private void clearHovers() { for (UUID id : Set.copyOf(hovers.keySet())) clearHover(id); }

    private void destroyDisplays() {
        clearHovers();
        for (Cell cell : cells) cell.display.remove();
        cells.clear();
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin);
        tickets.clear();
    }

    private void close() {
        destroyDisplays();
        layout = null;
        columns = 0;
        rowsPerColumn = 0;
        clicks.clear();
    }
}
