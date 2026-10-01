package me.advait.contender.minigame.race;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.display.Theme;
import me.advait.contender.util.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

/**
 * Builds a course in the world. Editors get a hotbar of tools; every checkpoint shows as a glowing mob
 * with its number, joined to the next one by a trail of particles.
 *
 *  1 Add       right-click: add a checkpoint where you stand (midair works). Sneak: on the block you look at.
 *  2 Mob type  right-click: next mob type. Left-click a checkpoint: change it to this type.
 *  3 Move      left-click a checkpoint to pick it up, right-click to put it where you stand.
 *  4 Insert    left-click a checkpoint, then right-click to add a new one before it.
 *  5 Remove    left-click a checkpoint to delete it. The rest renumber.
 *  7 Start     right-click: the race starts where you stand, facing your way.
 *  8 Settings  right-click: course rules and options.
 *  9 Done      right-click: stop editing and get your items back.
 */
public final class RaceEditor implements Listener, me.advait.contender.activity.Activity {
    private static final NamespacedKey TOOL = new NamespacedKey("contender", "race_tool");
    private static final String PREVIEW = "race_preview";

    private final Contender plugin;
    private final RaceCourses courses;
    private final Consumer<Player> openSettings;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, List<LivingEntity>> previews = new HashMap<>();
    private BukkitTask particles;

    private static final class Session {
        final String course;
        EntityType type = EntityType.ZOMBIE;
        int moving = -1, insertBefore = -1;
        Session(String course) { this.course = course; }
    }

    public RaceEditor(Contender plugin, RaceCourses courses, Consumer<Player> openSettings) {
        this.plugin = plugin;
        this.courses = courses;
        this.openSettings = openSettings;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        particles = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drawPaths, 10, 10);
    }

    public void disable() {
        if (particles != null) particles.cancel();
        forceStop("Plugin stopping");
        previews.values().forEach(list -> list.forEach(Entity::remove));
        previews.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    public boolean editing(Player player) { return sessions.containsKey(player.getUniqueId()); }

    @Override public String displayName() { return "Course building"; }

    /** Editors keep their tools until they click Done; a disconnect simply ends the session. */
    @Override public void handleJoin(Player player) {
        if (!sessions.containsKey(player.getUniqueId())) {
            plugin.getRegistry().release(player.getUniqueId(), this);
            plugin.getSnapshots().restoreToLobby(player);
        }
    }

    @Override public void forceStop(String reason) {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) stop(player);
            else { Session session = sessions.remove(id); plugin.getRegistry().release(id, this); if (session != null) refreshPreview(session.course); }
        }
    }

    public String editingCourse(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : session.course;
    }

    public boolean courseBeingEdited(String course) { return sessions.values().stream().anyMatch(s -> s.course.equals(course)); }

    // ---- Sessions ------------------------------------------------------------------------------

    public void start(Player player, RaceCourse course) {
        if (!plugin.getRegistry().isFree(player.getUniqueId()) && plugin.getRegistry().owner(player.getUniqueId()) != this) {
            throw new IllegalStateException("Finish what you're playing or watching first.");
        }
        if (course.world() != null && !player.getWorld().equals(course.world()) && (course.start() != null || course.size() > 0)) {
            Location target = course.startLocation() != null ? course.startLocation() : course.checkpoints().getFirst().at(course.world());
            player.teleport(target);
        }
        Session previous = sessions.get(player.getUniqueId());
        if (previous == null) {
            plugin.getSnapshots().capture(player);
            plugin.getRegistry().claim(player.getUniqueId(), this, me.advait.contender.activity.ActivityRegistry.Involvement.PLAYING);
            player.setGameMode(GameMode.CREATIVE);
        }
        sessions.put(player.getUniqueId(), new Session(course.id()));
        if (previous != null && !previous.course.equals(course.id())) refreshPreview(previous.course);
        giveTools(player, sessions.get(player.getUniqueId()));
        refreshPreview(course.id());
        Msg.success(player, "Editing " + course.name() + ". Hover a tool to see what it does.");
        player.sendMessage(Msg.text("Add checkpoints in order. The last one is the finish.", DialogPalette.MUTED));
    }

    public void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        player.getInventory().clear();
        plugin.getRegistry().release(player.getUniqueId(), this);
        plugin.getSnapshots().restore(player);
        refreshPreview(session.course);
        courses.save();
    }

    private void giveTools(Player player, Session session) {
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setItem(0, tool(Material.BLAZE_ROD, "add", "Add Checkpoint", "Right-click: add one where you stand", "Sneak + right-click: on the block you look at"));
        inventory.setItem(1, typeTool(session.type));
        inventory.setItem(2, tool(Material.LEAD, "move", "Move Checkpoint", "Left-click a checkpoint to pick it up", "Right-click to put it where you stand"));
        inventory.setItem(3, tool(Material.STICK, "insert", "Insert Checkpoint", "Left-click a checkpoint", "then right-click to add one before it"));
        inventory.setItem(4, tool(Material.SHEARS, "remove", "Remove Checkpoint", "Left-click a checkpoint to delete it"));
        inventory.setItem(6, tool(Material.RED_BED, "start", "Set Start", "Right-click: racers start here, facing your way"));
        inventory.setItem(7, tool(Material.BOOK, "settings", "Course Settings", "Right-click: rules and options"));
        inventory.setItem(8, tool(Material.LIME_DYE, "done", "Done", "Right-click: stop editing"));
        inventory.setHeldItemSlot(0);
    }

    private ItemStack typeTool(EntityType type) {
        Material egg = Material.matchMaterial(type.name() + "_SPAWN_EGG");
        return tool(egg == null ? Material.ZOMBIE_SPAWN_EGG : egg, "type", "Mob: " + pretty(type),
                "Right-click: next mob type (sneak: previous)", "Left-click a checkpoint: change it to this mob");
    }

    private static String pretty(EntityType type) {
        String name = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static ItemStack tool(Material material, String id, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, DialogPalette.ACCENT).decoration(TextDecoration.ITALIC, false));
            meta.lore(Arrays.stream(lore).map(line -> (Component) Component.text(line, DialogPalette.MUTED).decoration(TextDecoration.ITALIC, false)).toList());
            meta.getPersistentDataContainer().set(TOOL, PersistentDataType.STRING, id);
        });
        return item;
    }

    private static String toolOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(TOOL, PersistentDataType.STRING);
    }

    // ---- Preview -------------------------------------------------------------------------------

    /** Respawns the glowing preview of a course, or clears it when nobody is editing. */
    public void refreshPreview(String courseId) {
        List<LivingEntity> old = previews.remove(courseId);
        if (old != null) old.forEach(Entity::remove);
        RaceCourse course = courses.get(courseId);
        if (course == null || course.world() == null || !courseBeingEdited(courseId)) return;
        Theme theme = plugin.getThemes().current();
        List<LivingEntity> spawned = new ArrayList<>();
        List<RaceCourse.Checkpoint> points = course.checkpoints();
        for (int i = 0; i < points.size(); i++) {
            Location at = points.get(i).at(course.world());
            if (!at.isChunkLoaded()) at.getChunk();
            spawned.add(RaceMobs.spawn(at, points.get(i).type(), Component.text(course.label(i), i == points.size() - 1 ? theme.primary() : theme.secondary()),
                    PREVIEW + ":" + courseId, true));
        }
        previews.put(courseId, spawned);
    }

    private void drawPaths() {
        Theme theme = plugin.getThemes().current();
        for (String id : previews.keySet()) {
            RaceCourse course = courses.get(id);
            if (course == null || course.world() == null) continue;
            World world = course.world();
            List<Location> path = new ArrayList<>();
            if (course.startLocation() != null) path.add(course.startLocation());
            for (RaceCourse.Checkpoint point : course.checkpoints()) path.add(point.at(world).add(0, 1, 0));
            Particle.DustOptions dust = new Particle.DustOptions(theme.accentColor(), 1.1f);
            for (int i = 0; i + 1 < path.size(); i++) {
                Location from = path.get(i), to = path.get(i + 1);
                double distance = from.distance(to);
                Vector step = to.toVector().subtract(from.toVector()).normalize().multiply(0.8);
                Location at = from.clone();
                for (double travelled = 0; travelled < Math.min(distance, 400); travelled += 0.8) {
                    world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
                    at.add(step);
                }
            }
            if (course.startLocation() != null) world.spawnParticle(Particle.HAPPY_VILLAGER, course.startLocation().add(0, 0.2, 0), 6, 0.4, 0.1, 0.4, 0);
        }
    }

    private int indexOf(Entity entity, String courseId) {
        List<LivingEntity> list = previews.get(courseId);
        return list == null ? -1 : list.indexOf(entity);
    }

    // ---- Tool use ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getHand() != EquipmentSlot.HAND) return;
        String tool = toolOf(event.getItem());
        if (tool == null) return;
        event.setCancelled(true);
        if (!event.getAction().isRightClick()) return;
        Player player = event.getPlayer();
        try { rightClick(player, session, tool, event.getAction() == Action.RIGHT_CLICK_BLOCK ? event.getClickedBlock() : null); }
        catch (RuntimeException failure) { Msg.error(player, Msg.reason(failure)); Sounds.ERROR.play(player); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseEntity(PlayerInteractEntityEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        String tool = toolOf(event.getPlayer().getInventory().getItem(event.getHand()));
        if (tool == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        try { rightClick(event.getPlayer(), session, tool, null); }
        catch (RuntimeException failure) { Msg.error(event.getPlayer(), Msg.reason(failure)); Sounds.ERROR.play(event.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(PrePlayerAttackEntityEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        String tool = toolOf(event.getPlayer().getInventory().getItemInMainHand());
        if (tool == null) return;
        event.setCancelled(true);
        int index = indexOf(event.getAttacked(), session.course);
        if (index < 0) { Msg.hint(event.getPlayer(), "Hit one of this course's glowing checkpoints."); return; }
        try { leftClick(event.getPlayer(), session, tool, index); }
        catch (RuntimeException failure) { Msg.error(event.getPlayer(), Msg.reason(failure)); Sounds.ERROR.play(event.getPlayer()); }
    }

    private RaceCourse course(Session session) {
        RaceCourse course = courses.get(session.course);
        if (course == null) throw new IllegalStateException("That course was deleted.");
        if (plugin.getMinigames().current() instanceof RaceGame game && !game.finished() && game.course().id().equals(course.id())) {
            throw new IllegalStateException("This course is being raced. Edit it after the race.");
        }
        return course;
    }

    private void rightClick(Player player, Session session, String tool, Block block) {
        RaceCourse course = course(session);
        Location here = player.getLocation();
        switch (tool) {
            case "add" -> {
                Location at = player.isSneaking() ? targeted(player) : here;
                if (session.insertBefore >= 0) {
                    course.insert(session.insertBefore, at, session.type);
                    Msg.success(player, "Added " + course.label(session.insertBefore) + ".");
                    session.insertBefore = -1;
                } else {
                    course.add(at, session.type);
                    Msg.success(player, "Added checkpoint " + course.size() + ". It's the finish until you add another.");
                }
                changed(player, course);
            }
            case "type" -> {
                int index = RaceMobs.TYPES.indexOf(session.type);
                int next = player.isSneaking() ? index - 1 : index + 1;
                session.type = RaceMobs.TYPES.get(Math.floorMod(next, RaceMobs.TYPES.size()));
                player.getInventory().setItem(1, typeTool(session.type));
                player.sendActionBar(Msg.text("New checkpoints: " + pretty(session.type), DialogPalette.ACCENT));
                Sounds.CLICK.play(player);
            }
            case "move" -> {
                if (session.moving < 0) { Msg.hint(player, "Left-click a checkpoint first."); return; }
                if (session.moving >= course.size()) { session.moving = -1; return; }
                course.move(session.moving, player.isSneaking() ? targeted(player) : here);
                Msg.success(player, "Moved " + course.label(session.moving) + ".");
                session.moving = -1;
                changed(player, course);
            }
            case "insert" -> {
                if (session.insertBefore < 0) { Msg.hint(player, "Left-click the checkpoint to insert before."); return; }
                course.insert(session.insertBefore, player.isSneaking() ? targeted(player) : here, session.type);
                Msg.success(player, "Inserted " + course.label(session.insertBefore) + ". Later checkpoints moved up a number.");
                session.insertBefore = -1;
                changed(player, course);
            }
            case "start" -> {
                course.setStart(here);
                Msg.success(player, "Start set here.");
                changed(player, course);
            }
            case "settings" -> openSettings.accept(player);
            case "done" -> { stop(player); Msg.success(player, "Course saved."); }
            default -> { }
        }
    }

    private void leftClick(Player player, Session session, String tool, int index) {
        RaceCourse course = course(session);
        switch (tool) {
            case "type" -> {
                course.retype(index, session.type);
                Msg.success(player, course.label(index) + " is now a " + pretty(session.type).toLowerCase(Locale.ROOT) + ".");
                changed(player, course);
            }
            case "move" -> {
                session.moving = index;
                player.sendActionBar(Msg.text("Picked up " + course.label(index) + ". Right-click to place it.", DialogPalette.ACCENT));
                Sounds.CLICK.play(player);
            }
            case "insert" -> {
                session.insertBefore = index;
                player.sendActionBar(Msg.text("Right-click to add a checkpoint before " + course.label(index) + ".", DialogPalette.ACCENT));
                Sounds.CLICK.play(player);
            }
            case "remove" -> {
                String label = course.label(index);
                course.remove(index);
                Msg.success(player, "Removed " + label + ". The rest were renumbered.");
                changed(player, course);
            }
            default -> Msg.hint(player, "Use the Mob, Move, Insert or Remove tool on checkpoints.");
        }
    }

    private Location targeted(Player player) {
        Block block = player.getTargetBlockExact(64, FluidCollisionMode.NEVER);
        if (block == null) throw new IllegalArgumentException("Look at a block within 64 blocks.");
        Location at = block.getLocation().add(0.5, 1, 0.5);
        at.setYaw(player.getLocation().getYaw() + 180);
        return at;
    }

    private void changed(Player player, RaceCourse course) {
        courses.save();
        refreshPreview(course.id());
        Sounds.POP.play(player);
    }

    // ---- Keep editing safe ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreviewDamage(EntityDamageEvent event) {
        String owner = Tags.owner(event.getEntity());
        if (owner != null && owner.startsWith(PREVIEW)) event.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        if (editing(event.getPlayer()) && toolOf(event.getItemDrop().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler public void onPlace(BlockPlaceEvent event) {
        if (editing(event.getPlayer()) && toolOf(event.getItemInHand()) != null) event.setCancelled(true);
    }

    @EventHandler public void onBreak(BlockBreakEvent event) {
        if (editing(event.getPlayer()) && toolOf(event.getPlayer().getInventory().getItemInMainHand()) != null) event.setCancelled(true);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { stop(event.getPlayer()); }
}
