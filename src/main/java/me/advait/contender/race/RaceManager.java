package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.dialog.Dialogs;
import me.advait.contender.tournament.TournamentEntry;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/** Courses and last results survive sessions; a session owns only its live arena and listeners. */
public final class RaceManager extends AbstractGameState {
    private final Contender contender;
    private final RaceStore store;
    private final RaceReturns returns;
    private final Map<String, RaceCourse> courses = new LinkedHashMap<>();
    private final Map<UUID, String> numbering = new HashMap<>(), markingFinish = new HashMap<>();
    private final Map<me.advait.contender.arena.ArenaLease, RaceSession> stranded = new HashMap<>();
    private RaceRun current;
    private RaceSession session;
    private boolean selected, resetting;
    public RaceManager(Contender plugin) {
        super(plugin); contender = plugin; store = new RaceStore(plugin.getDataFolder()); returns = new RaceReturns(plugin);
    }
    @Override protected void onEnable() {
        var saved = store.load(); courses.putAll(saved.courses()); current = saved.run(); selected = saved.selected(); save();
        runRepeating(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) if (!owns(player.getUniqueId()) && returns.pending(player.getUniqueId())) {
                try { restore(player); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not restore race inventory", failure); }
            }
            for (var entry : List.copyOf(stranded.entrySet())) if (Bukkit.getOnlinePlayers().stream().noneMatch(p -> entry.getValue().contains(p.getLocation()))) {
                contender.getArenaManager().release(entry.getKey()); stranded.remove(entry.getKey());
            }
        }, 20, 100);
    }
    @Override protected void onDisable() {
        if (session != null) { current.end(RaceRun.State.INTERRUPTED); try { save(); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not save interrupted race", failure); } RaceSession old = session; session = null; old.disable(); contender.getArenaManager().discard(old.lease()); contender.getArenaManager().release(old.lease()); }
        stranded.keySet().forEach(contender.getArenaManager()::release); stranded.clear();
        numbering.clear(); markingFinish.clear();
    }
    public RaceRun current() { return current; }
    public RaceRun displayed() { return selected ? current : null; }
    public boolean active() { return session != null; }
    public boolean busy() { return active() || resetting || !stranded.isEmpty(); }
    public boolean owns(UUID id) { return session != null && session.owns(id); }
    public boolean isReserved(UUID id) { return current != null && !current.terminal() && current.racer(id) != null; }
    public boolean isRacing(UUID id) { return session != null && session.racing(id); }
    public boolean inArena(Location location) { return session != null && session.contains(location); }
    public boolean pendingReturn(UUID id) { return returns.pending(id); }
    public List<RaceCourse> courses() { return List.copyOf(courses.values()); }
    public RaceCourse course(String mapId) { return courses.getOrDefault(mapId, RaceCourse.defaults(mapId)); }
    public void selectRoundRobin() {
        if (current != null && !current.terminal()) throw new IllegalStateException("Finish or cancel the Mace Race first.");
        selected = false; save();
    }
    public void configure(RaceCourse course) {
        if ((busy() || current != null && !current.terminal()) && current != null && current.mapId().equals(course.mapId())) throw new IllegalStateException("Wait for the race and arena reset to finish.");
        requireMap(course.mapId()); courses.put(course.mapId(), course); save();
    }
    public void create(String name, String mapId, int advance, int minutes, List<TournamentEntry> entries) {
        create(name, mapId, advance, minutes, entries, "");
    }
    public void create(String name, String mapId, int advance, int minutes, List<TournamentEntry> entries, String kitId) {
        if (contender.getMinigameManager() != null) contender.getMinigameManager().beforeCreate("mace_race");
        selectedKit(kitId);
        if (busy() || current != null && !current.terminal()) throw new IllegalStateException("Finish or cancel the current race first.");
        var tournament = contender.getTournamentManager().current();
        if (tournament != null && !tournament.isComplete() && !tournament.isCancelled()) throw new IllegalStateException("Finish or cancel the round robin first.");
        if (!courses.containsKey(mapId)) throw new IllegalArgumentException("Save this map as a race course first.");
        ArenaMap map = requireMap(mapId);
        if (!map.isComplete()) throw new IllegalStateException("Set the course start and prepare its arena copies first.");
        List<RaceRun.Racer> roster = new ArrayList<>();
        for (var entry : entries) for (UUID id : entry.players()) {
            contender.getDuelManager().requireEligible(id);
            roster.add(new RaceRun.Racer(id, entry.playerNames().getOrDefault(id, entry.name())));
        }
        RaceRun next = new RaceRun(UUID.randomUUID(), name, mapId, advance, Math.multiplyExact(minutes, 60), roster, kitId);
        RaceRun previous = current; boolean wasSelected = selected;
        current = next; selected = true;
        try { save(); } catch (RuntimeException failure) { current = previous; selected = wasSelected; throw failure; }
        if (contender.getMinigameManager() != null) contender.getMinigameManager().select("mace_race");
        refresh();
    }
    me.advait.contender.kit.Kit selectedKit(String kitId) {
        if (kitId == null || kitId.isBlank()) return null;
        var kit = contender.getKitManager().getKit(kitId);
        if (kit == null) throw new IllegalArgumentException("The race kit was deleted. Choose another kit.");
        RaceKit.validate(kit); return kit;
    }
    public void start(UUID id) {
        requireCurrent(id);
        if (contender.getMinigameManager() != null) contender.getMinigameManager().beforeStart("mace_race");
        selectedKit(current.kitId());
        if (busy() || current.state() != RaceRun.State.READY) throw new IllegalStateException("This race cannot be started.");
        if (contender.getVoteManager().isVoteActive()) throw new IllegalStateException("Wait for the vote to finish.");
        if (contender.getLobbyManager().getLobbyLocation() == null) throw new IllegalStateException("Set a lobby with /setlobby first.");
        if (current.allDone()) throw new IllegalStateException("There are no racers left to start.");
        for (var racer : current.racers()) if (!racer.done()) requireAvailable(racer);
        var lease = contender.getArenaManager().acquire(current.mapId());
        if (lease == null) throw new IllegalStateException("No course copies are ready. Prepare them in Maps first.");
        session = new RaceSession(contender, this, current, course(current.mapId()), lease);
        try { session.enable(); } catch (RuntimeException | Error failure) { failed(failure); throw failure; }
    }
    public void requireCurrent(UUID id) {
        if (current == null || !current.id().equals(id)) throw new IllegalStateException("That race is no longer selected.");
    }
    void requireAvailable(RaceRun.Racer racer) {
        Player player = Bukkit.getPlayer(racer.id());
        if (player == null || player.isDead()) throw new IllegalStateException(racer.name() + " must be online and alive to start.");
        contender.getDuelManager().requireEligible(racer.id());
        if (contender.getDuelManager().getDuel(racer.id()) != null) throw new IllegalStateException(racer.name() + " must leave their match first.");
        if (contender.getMinigameManager() != null && contender.getMinigameManager().pendingReturn(racer.id()) || returns.pending(racer.id())) throw new IllegalStateException("Return " + racer.name() + " to the lobby before starting.");
    }
    public void end(RaceRun.State state) {
        if (current == null || current.terminal()) return;
        current.end(state);
        try { save(); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not save race results", failure); }
        RaceSession previous = session; session = null;
        if (previous != null) {
            previous.disable(); resetting = true;
            // Never paste an arena over a player whose lobby teleport was denied.
            boolean occupied = Bukkit.getOnlinePlayers().stream().anyMatch(p -> previous.contains(p.getLocation()));
            if (occupied) {
                contender.getArenaManager().discard(previous.lease()); stranded.put(previous.lease(), previous); resetting = false;
                contender.getLogger().warning("Race arena remains reserved until everyone has left it. Pending lobby returns will be retried.");
            }
            else contender.getArenaManager().reset(previous.lease()).whenComplete((ignored, failure) -> {
                if (failure != null) contender.getArenaManager().discard(previous.lease());
                contender.getArenaManager().release(previous.lease());
                resetting = false;
                if (failure != null) contender.getLogger().log(Level.SEVERE, "Could not reset race arena", failure);
            });
        }
        refresh();
    }
    void failed(Throwable failure) {
        contender.getLogger().log(Level.SEVERE, "Mace Race stopped", failure);
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("contender.master"))
                .forEach(p -> Dialogs.tell(p, "Mace Race stopped: " + rootMessage(failure)));
        end(RaceRun.State.INTERRUPTED);
    }
    public void withdraw(UUID id) {
        if (current == null || current.racer(id) == null || current.terminal()) return;
        if (session != null) session.withdraw(id); else { current.withdraw(id); save(); if (current.allDone()) end(RaceRun.State.FINISHED); }
        refresh();
    }
    void capture(Player player) { returns.capture(player); }
    public boolean restore(Player player) { return returns.restore(player); }
    public void save() { store.save(courses, current, selected); }
    private void refresh() {
        if (contender.getTabManager() != null) contender.getTabManager().refresh();
        if (contender.getTournamentManager() != null) contender.getTournamentManager().board().update(contender.getTournamentManager().current(), contender.getTournamentManager().playing());
    }
    public void setStart(String mapId, Player player) {
        ArenaMap map = requireMap(mapId);
        contender.getArenaManager().setSpawn(map, "1", player.getLocation());
        // Race maps share the pool format. The second team spawn is unused in a race.
        if (map.getTeam2Point() == null) contender.getArenaManager().setSpawn(map, "2", player.getLocation());
    }
    public CompletableFuture<Void> saveCourse(String mapId) {
        ArenaMap map = requireMap(mapId);
        if (busy()) throw new IllegalStateException("Wait for the race and arena reset to finish.");
        RaceCourse course = course(mapId); Set<Chunk> tickets = new HashSet<>();
        return loadChunks(contender, map, tickets, this::isEnabled).thenCompose(ignored -> {
            scan(map, course); configure(course);
            return contender.getArenaManager().saveBlocks(map).thenCompose(done -> contender.getArenaManager().prepare(map));
        }).whenComplete((ignored, failure) -> tickets.forEach(c -> c.removePluginChunkTicket(plugin)));
    }
    public void numberSpawns(Player player, String mapId) {
        ArenaMap source = requireMap(mapId);
        if (!source.contains(player.getLocation())) throw new IllegalArgumentException("Stand inside the map selection to number mobs.");
        if (mapId.equals(numbering.get(player.getUniqueId()))) { numbering.remove(player.getUniqueId()); Dialogs.tell(player, "Checkpoint numbering stopped."); return; }
        if (numbering.containsValue(mapId)) throw new IllegalStateException("Someone is already numbering mobs in this map.");
        numbering.put(player.getUniqueId(), mapId); markingFinish.remove(player.getUniqueId());
        Dialogs.tell(player, "Spawn mobs inside the selection to number them. Reopen this menu to stop.");
    }
    public boolean numbering(Player player, String mapId) { return mapId.equals(numbering.get(player.getUniqueId())); }
    public void markFinish(Player player, String mapId) {
        requireMap(mapId); markingFinish.put(player.getUniqueId(), mapId); numbering.remove(player.getUniqueId());
        Dialogs.tell(player, "Right-click the finish mob inside the map selection.");
    }
    private ArenaMap requireMap(String id) {
        ArenaMap map = contender.getMapManager().getMap(id);
        if (map == null || map.getBounds() == null) throw new IllegalArgumentException("Save a map selection first.");
        return map;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.COMMAND
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) return;
        for (String id : numbering.values()) {
            ArenaMap map = requireMap(id); if (!map.contains(event.getLocation())) continue;
            int next = entities(map).stream().mapToInt(e -> course(id).checkpoint(plainName(e)))
                    .filter(n -> n > 0 && n < Integer.MAX_VALUE).max().orElse(0) + 1;
            event.getEntity().customName(Component.text("#" + next, NamedTextColor.GOLD));
            event.getEntity().setCustomNameVisible(true); event.getEntity().setAI(false); event.getEntity().setGravity(false); event.getEntity().setPersistent(true);
            break;
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void finish(PlayerInteractEntityEvent event) {
        String mapId = markingFinish.get(event.getPlayer().getUniqueId()); if (mapId == null || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        event.setCancelled(true);
        if (!(event.getRightClicked() instanceof LivingEntity mob) || mob instanceof Player || !requireMap(mapId).contains(mob.getLocation())) {
            Dialogs.tell(event.getPlayer(), "Choose a mob inside the map selection."); return;
        }
        mob.customName(Component.text(course(mapId).finishName(), NamedTextColor.GOLD)); mob.setCustomNameVisible(true);
        mob.setAI(false); mob.setGravity(false); mob.setPersistent(true); markingFinish.remove(event.getPlayer().getUniqueId());
        Dialogs.tell(event.getPlayer(), "Finish mob set. Save the course to update its copies.");
    }
    @EventHandler public void quit(PlayerQuitEvent e) { numbering.remove(e.getPlayer().getUniqueId()); markingFinish.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        runLater(() -> { if (e.getPlayer().isOnline() && !owns(e.getPlayer().getUniqueId())) restore(e.getPlayer()); }, 3);
    }
    static String plainName(Entity entity) { return entity.customName() == null ? null : PlainTextComponentSerializer.plainText().serialize(entity.customName()); }
    static List<LivingEntity> entities(ArenaMap map) {
        World world = Bukkit.getWorld(map.getWorldName());
        if (world == null) throw new IllegalArgumentException("Load the map world first.");
        List<LivingEntity> entities = new ArrayList<>(); var b = map.getBounds();
        for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
            for (Entity entity : world.getChunkAt(x, z).getEntities()) if (entity instanceof LivingEntity living && !(entity instanceof Player) && map.contains(entity.getLocation())) entities.add(living);
        }
        return entities;
    }
    static Map<Integer, LivingEntity> scan(ArenaMap map, RaceCourse course) {
        Map<Integer, LivingEntity> found = new TreeMap<>();
        for (LivingEntity entity : entities(map)) {
            int n = course.checkpoint(plainName(entity)); if (n < 0) continue;
            if (found.putIfAbsent(n, entity) != null) throw new IllegalArgumentException("Duplicate checkpoint: " + plainName(entity));
        }
        if (!found.containsKey(Integer.MAX_VALUE)) throw new IllegalArgumentException("Name one finish mob '" + course.finishName() + "'.");
        if (found.size() < 2) throw new IllegalArgumentException("Add checkpoint mobs named #1, #2, and so on.");
        for (int n = 1; n < found.size(); n++) if (!found.containsKey(n)) throw new IllegalArgumentException("Checkpoint #" + n + " is missing.");
        return found;
    }
    static CompletableFuture<Void> loadChunks(Contender plugin, ArenaMap map, Set<Chunk> tickets, BooleanSupplier active) {
        return me.advait.contender.minigame.MinigameArenas.loadChunks(plugin, map, tickets, active);
    }
    public static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
