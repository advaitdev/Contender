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
import org.bukkit.util.RayTraceResult;
import org.bukkit.block.BlockFace;
import org.joml.Matrix4f;

import java.util.*;

/**
 * The tab bracket, drawn with text displays in the lobby. Point at a live match and swing to watch it;
 * the controls underneath switch rounds, standings and pages.
 *
 * <p>Everyone sees the live board: the current round, first page. A player who uses a control gets their own
 * copy that only they can see, so switching pages never changes what anyone else (or a camera) sees.
 * "Back to live view" returns them to the shared board.
 */
public final class StageBoard extends Module {
    /** Text scale: bigger than a default display so it reads from across the lobby. */
    private static final float SCALE = 1.15f;
    private static final double ROW_HEIGHT = 0.30 * SCALE;
    private static final int ROWS = 20;
    /** Space under the last row for the caption and the three rows of controls. */
    private static final double FOOTER = 1.8 * SCALE;
    /** Space above the top row for the title. */
    private static final double TITLE = 1.4 * SCALE;
    /** How far away a wall can be when hanging the board on it. */
    private static final double WALL_REACH = 32;
    private static final int BACK_TO_LIVE = -6;
    private final Map<UUID, Hover> hovers = new HashMap<>();
    private final Map<UUID, Long> clicks = new HashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    // Standing boards grow upward from base (just above the floor). A board on a wall is centered on wallCenter
    // instead, but never reaches below wallFloor; base is then the same spot, for the checks that need a location.
    private Location base;
    private Location wallCenter;
    private double wallFloor;
    private Vector normal, right;
    private UUID stageId;
    private final Board shared = new Board(null);
    private final Map<UUID, Board> personal = new HashMap<>();

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
        Location location = new Location(world, config.getDouble("tournament-board.x"), y,
                config.getDouble("tournament-board.z"), (float) config.getDouble("tournament-board.yaw", 180), 0);
        if (config.getBoolean("tournament-board.wall", false)) setWall(location, config.getDouble("tournament-board.floor", y - 32));
        else setBase(location);
    }

    public boolean placed() { return base != null; }

    /**
     * Looking at a wall (up to 32 blocks away) hangs the board flat on it, centered where you look. Otherwise the
     * board stands on the floor eight blocks in front of you, facing you. Returns true when it went on a wall.
     */
    public boolean place(Player player) {
        if (plugin.getArenas().isArenaWorld(player.getWorld())) throw new IllegalArgumentException("Place the board outside the arena world.");
        RayTraceResult hit = player.rayTraceBlocks(WALL_REACH, FluidCollisionMode.NEVER);
        BlockFace face = hit == null ? null : hit.getHitBlockFace();
        var config = plugin.getConfig();
        Location location;
        boolean wall = face != null && face.getModY() == 0;
        if (wall) {
            Vector out = face.getDirection();
            location = hit.getHitPosition().toLocation(player.getWorld()).add(out.clone().multiply(0.05));
            location.setDirection(out);
            location.setPitch(0);
            double floor = floorBelow(location);
            setWall(location, floor);
            config.set("tournament-board.floor", floor);
        } else {
            Vector direction = player.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() < 0.001) direction.setZ(1);
            location = player.getLocation().add(direction.normalize().multiply(8)).add(0, 0.3, 0);
            location.setYaw(player.getLocation().getYaw() + 180);
            location.setPitch(0);
            setBase(location);
            config.set("tournament-board.floor", null);
        }
        config.set("tournament-board.version", 3);
        config.set("tournament-board.wall", wall);
        config.set("tournament-board.world", location.getWorld().getName());
        config.set("tournament-board.x", location.getX());
        config.set("tournament-board.y", location.getY());
        config.set("tournament-board.z", location.getZ());
        config.set("tournament-board.yaw", location.getYaw());
        plugin.saveConfig();
        refresh();
        return wall;
    }

    /** The top of the first solid block under this spot, so a wall board never sinks into the floor. */
    private static double floorBelow(Location location) {
        int top = location.getBlockY();
        for (int y = top; y > top - 32 && y > location.getWorld().getMinHeight(); y--) {
            var block = location.getWorld().getBlockAt(location.getBlockX(), y, location.getBlockZ());
            if (block.getType().isSolid()) return y + 1;
        }
        return location.getY() - 32;
    }

    /** Where the board's bottom edge goes for this many rows. */
    private Location bottom(int rows) {
        if (wallCenter == null) return base.clone();
        double height = rows * ROW_HEIGHT + FOOTER + TITLE;
        Location bottom = wallCenter.clone();
        bottom.setY(Math.max(wallFloor + 0.1, wallCenter.getY() - height / 2));
        return bottom;
    }

    public void remove() {
        close();
        base = null;
        wallCenter = null;
        for (String key : List.of("world", "x", "y", "z", "yaw", "version", "wall", "floor")) plugin.getConfig().set("tournament-board." + key, null);
        plugin.saveConfig();
    }

    private void setWall(Location center, double floor) {
        setBase(center);
        wallCenter = center.clone();
        wallFloor = floor;
    }

    private void setBase(Location location) {
        close();
        wallCenter = null;
        base = location.clone();
        normal = base.getDirection();
        double yaw = Math.toRadians(base.getYaw());
        right = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
    }

    /** Redraws after a theme or opacity change. Private views keep their round and page. */
    public void rebuild() {
        clearHovers();
        shared.destroy();
        personal.values().forEach(Board::destroy);
        refresh();
    }

    public void refresh() {
        if (!isEnabled() || base == null || base.getWorld() == null || !base.isChunkLoaded() && base.getWorld().getPlayers().isEmpty()) return;
        Stage stage = plugin.getStages().current();
        if (stage != null && stage.cancelled()) stage = null;
        UUID id = stage == null ? null : stage.id();
        if (!Objects.equals(stageId, id)) {
            // A new event starts everyone on the live board again.
            stageId = id;
            for (UUID owner : Set.copyOf(personal.keySet())) closePersonal(owner);
            clearHovers();
        }
        shared.refresh(stage);
        for (Board board : List.copyOf(personal.values())) {
            if (Bukkit.getPlayer(board.owner) == null) closePersonal(board.owner);
            else board.refresh(stage);
        }
    }

    private Stage currentStage() {
        Stage stage = plugin.getStages().current();
        return stage != null && stage.cancelled() ? null : stage;
    }

    /** The board this player sees. */
    private Board boardFor(Player player) {
        Board own = personal.get(player.getUniqueId());
        return own != null ? own : shared;
    }

    private Board openPersonal(Player player) {
        clearHover(player.getUniqueId());
        Board board = new Board(player.getUniqueId());
        personal.put(player.getUniqueId(), board);
        for (Cell cell : shared.cells) player.hideEntity(plugin, cell.display);
        return board;
    }

    private void closePersonal(UUID owner) {
        clearHover(owner);
        Board board = personal.remove(owner);
        if (board == null) return;
        board.destroy();
        Player player = Bukkit.getPlayer(owner);
        if (player != null) for (Cell cell : shared.cells) player.showEntity(plugin, cell.display);
    }

    /** One drawing of the board: the shared one (owner null) or a player's private view. */
    private final class Board {
        final UUID owner;
        final List<Cell> cells = new ArrayList<>();
        Location anchor;
        int rowsPerColumn, columns, columnPixels;
        BracketLayout.View view = BracketLayout.View.following();
        BracketLayout.Layout layout;

        Board(UUID owner) { this.owner = owner; }

        void refresh(Stage stage) {
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
                if (owner != null) clearHover(owner);
                else clearHovers();
                destroy();
                columns = newColumns;
                columnPixels = pixels;
                rowsPerColumn = newRows;
                anchor = bottom(rowsPerColumn).add(0, rowsPerColumn * ROW_HEIGHT + FOOTER, 0);
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
            // The shared board always shows the live view, so only a private view needs a way back.
            set(cells.get(offset++), owner == null ? Component.empty() : DialogIcon.REFRESH.label("Back to live view"), owner == null ? null : BACK_TO_LIVE);
            boolean pages = layout.pages() > 1;
            set(cells.get(offset++), pages ? DialogIcon.BACK.label("Previous page") : Component.empty(), pages ? -1 : null);
            set(cells.get(offset), pages ? DialogIcon.NEXT.label("Next page") : Component.empty(), pages ? -2 : null);
        }

        Cell cell(double x, double y, double width) {
            Location location = anchor.clone().add(right.clone().multiply(x)).add(0, y, 0);
            TextDisplay display = spawn(location, owner == null);
            if (owner == null) {
                for (UUID viewer : personal.keySet()) { Player player = Bukkit.getPlayer(viewer); if (player != null) player.hideEntity(plugin, display); }
            } else {
                Player player = Bukkit.getPlayer(owner);
                if (player != null) player.showEntity(plugin, display);
            }
            return new Cell(display, x, y + ROW_HEIGHT / 2, width);
        }

        Cell selected(Player player) {
            if (anchor == null || !player.getWorld().equals(anchor.getWorld())) return null;
            Location eye = player.getEyeLocation();
            BoardHitbox.Hit hit = BoardHitbox.intersect(eye.toVector(), eye.getDirection(), anchor.toVector(), normal, right, 24);
            if (hit == null) return null;
            if (player.getWorld().rayTraceBlocks(eye, eye.getDirection(), hit.distance(), FluidCollisionMode.NEVER, true) != null) return null;
            for (Cell cell : cells) if (cell.target != null && BoardHitbox.contains(hit, cell.x, cell.y, cell.width, ROW_HEIGHT)) return cell;
            return null;
        }

        void destroy() {
            for (Cell cell : cells) cell.display.remove();
            cells.clear();
            columns = 0;
            rowsPerColumn = 0;
            layout = null;
        }
    }

    private TextDisplay spawn(Location location, boolean visibleToAll) {
        Chunk chunk = location.getChunk();
        if (!tickets.contains(chunk) && chunk.addPluginChunkTicket(plugin)) tickets.add(chunk);
        Theme theme = plugin.getThemes().current();
        return location.getWorld().spawn(location, TextDisplay.class, entity -> {
            Tags.managed(entity, "board");
            entity.setVisibleByDefault(visibleToAll);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            entity.setLineWidth(16384);
            entity.setViewRange(1.5f);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(theme.background(plugin.getThemes().opacity()));
            // Lit like a sign, so the board reads the same on a shaded wall as in the open.
            entity.setBrightness(new Display.Brightness(15, 15));
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

    private void hover() {
        if (base == null) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Cell selected = boardFor(player).selected(player);
            Hover previous = hovers.get(player.getUniqueId());
            if (previous != null && previous.cell() == selected) continue;
            clearHover(player.getUniqueId());
            if (selected == null) continue;
            Location location = selected.display.getLocation().add(normal.clone().multiply(0.04));
            TextDisplay display = spawn(location, false);
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
        Board board = boardFor(player);
        Cell cell = board.selected(player);
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
            } else if (target == BACK_TO_LIVE) {
                closePersonal(player.getUniqueId());
            } else if (board.layout != null) {
                BracketLayout.View view = board.view;
                BracketLayout.Layout layout = board.layout;
                BracketLayout.View next = switch (target) {
                    case -1 -> new BracketLayout.View(view.round(), layout.page() - 1, view.standings());
                    case -2 -> new BracketLayout.View(view.round(), layout.page() + 1, view.standings());
                    case -3 -> new BracketLayout.View(layout.round() - 1, 0, false);
                    case -4 -> new BracketLayout.View(layout.round() + 1, 0, false);
                    case -5 -> new BracketLayout.View(view.round(), 0, !view.standings());
                    default -> BracketLayout.View.following();
                };
                Board own = personal.get(player.getUniqueId());
                if (own == null) own = openPersonal(player);
                own.view = next;
                own.refresh(currentStage());
            }
        } catch (RuntimeException failure) { Msg.error(player, Msg.reason(failure)); }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        closePersonal(event.getPlayer().getUniqueId());
        clicks.remove(event.getPlayer().getUniqueId());
    }

    private void clearHover(UUID id) {
        Hover hover = hovers.remove(id);
        if (hover == null) return;
        hover.display().remove();
        if (hover.player().isOnline() && hover.cell().display.isValid()) hover.player().showEntity(plugin, hover.cell().display);
    }

    private void clearHovers() { for (UUID id : Set.copyOf(hovers.keySet())) clearHover(id); }

    private void close() {
        clearHovers();
        for (UUID owner : Set.copyOf(personal.keySet())) closePersonal(owner);
        shared.destroy();
        shared.view = BracketLayout.View.following();
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin);
        tickets.clear();
        clicks.clear();
    }
}
