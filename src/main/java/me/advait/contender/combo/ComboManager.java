package me.advait.contender.combo;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.dialog.ComboDialogs;
import me.advait.contender.dialog.Dialogs;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.minigame.MinigameMode;
import me.advait.contender.minigame.MinigameStanding;
import me.advait.contender.minigame.PlayerReturnStore;
import me.advait.contender.tournament.TournamentEntry;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import java.util.*;
import java.util.logging.Level;

public final class ComboManager extends AbstractGameState implements MinigameMode {
    private final Contender contender;
    private final ComboStore store;
    private final PlayerReturnStore returns;
    private final ComboDialogs dialogs;
    private final Map<ArenaLease, ComboSession> stranded = new HashMap<>();
    private ComboRun current;
    private ComboSession session;
    private boolean resetting;
    public ComboManager(Contender plugin) {
        super(plugin); contender = plugin; store = new ComboStore(plugin.getDataFolder());
        returns = new PlayerReturnStore(plugin, "combo-returns.yml"); dialogs = new ComboDialogs(plugin);
    }
    @Override protected void onEnable() {
        current = store.load(); save();
        runRepeating(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) if (!owns(player.getUniqueId()) && pendingReturn(player.getUniqueId())) {
                try { restore(player); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not restore Combo inventory", failure); }
            }
            for (var entry : List.copyOf(stranded.entrySet())) if (Bukkit.getOnlinePlayers().stream().noneMatch(p -> entry.getValue().contains(p.getLocation()))) {
                contender.getArenaManager().release(entry.getKey()); stranded.remove(entry.getKey());
            }
        }, 20, 100);
    }
    @Override protected void onDisable() {
        if (session != null) {
            current.end(ComboRun.State.INTERRUPTED); safeSave();
            ComboSession old = session; session = null; old.disable();
            contender.getArenaManager().discard(old.lease()); contender.getArenaManager().release(old.lease());
        }
        stranded.keySet().forEach(contender.getArenaManager()::release); stranded.clear();
    }
    @Override public String id() { return "combo"; }
    @Override public String displayName() { return "Combo"; }
    @Override public UUID eventId() { return current == null ? null : current.id(); }
    @Override public String eventName() { return current == null ? "Combo" : current.name(); }
    @Override public String statusText() { return current == null ? "Ready" : current.statusText(); }
    @Override public boolean terminal() { return current == null || current.terminal(); }
    @Override public boolean cancelled() { return current != null && current.state() == ComboRun.State.CANCELLED; }
    @Override public boolean active() { return session != null; }
    @Override public boolean busy() { return active() || resetting || !stranded.isEmpty(); }
    @Override public boolean isReserved(UUID id) { return current != null && !current.terminal() && current.entry(id) != null && !current.entry(id).done(); }
    @Override public boolean owns(UUID id) { return session != null && session.owns(id); }
    @Override public boolean isPlaying(UUID id) { return session != null && session.playing(id); }
    @Override public boolean isSpectator(UUID id) { return owns(id) && !isPlaying(id); }
    @Override public boolean pendingReturn(UUID id) { return returns.pending(id); }
    @Override public boolean restore(Player player) { return returns.restore(player); }
    @Override public boolean inArena(Location location) { return session != null && session.contains(location); }
    @Override public void open(Player player) { dialogs.open(player); }
    @Override public void createDialog(Player player) { dialogs.create(player); }
    @Override public void setupDialog(Player player) { dialogs.maps(player, 0); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { runLater(() -> recover(event.getPlayer()), 1); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) { runLater(() -> recover(event.getPlayer()), 1); }
    private void recover(Player player) {
        if (!player.isOnline() || player.isDead() || owns(player.getUniqueId()) || !pendingReturn(player.getUniqueId())) return;
        try { restore(player); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not restore Combo inventory", failure); }
    }
    public ComboRun current() { return current; }
    @Override public List<MinigameStanding> standings() {
        if (current == null) return List.of();
        return current.standings().stream().map(e -> new MinigameStanding(e.id(), e.name(),
                Objects.equals(e.id(), current.playing()) ? Integer.toString(current.hits()) : e.value(),
                e.cleared() ? NamedTextColor.GREEN : e.score() != null ? NamedTextColor.GOLD : e.withdrawn() ? NamedTextColor.GRAY : NamedTextColor.WHITE)).toList();
    }
    public void create(String name, String mapId, String kitId, ComboDifficulty difficulty, int grace, List<TournamentEntry> roster) {
        contender.getMinigameManager().beforeCreate(id());
        if (busy() || !terminal()) throw new IllegalStateException("Finish or cancel the current Combo event first.");
        var map = contender.getMapManager().getMap(mapId);
        if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with player and bot spawns.");
        requireSwordKit(kitId);
        List<ComboRun.Entry> entries = new ArrayList<>();
        for (var entry : roster) for (UUID id : entry.players()) {
            contender.getDuelManager().requireEligible(id);
            entries.add(new ComboRun.Entry(id, entry.playerNames().getOrDefault(id, entry.name())));
        }
        ComboRun next = new ComboRun(UUID.randomUUID(), name, mapId, kitId, difficulty, grace, entries), previous = current;
        current = next;
        try { save(); } catch (RuntimeException failure) { current = previous; throw failure; }
        contender.getMinigameManager().select(id()); refresh();
    }
    public void requireCurrent(UUID id) { if (current == null || !current.id().equals(id)) throw new IllegalStateException("That Combo event has been replaced."); }
    public void start(UUID id) {
        requireCurrent(id); contender.getMinigameManager().beforeStart(this.id());
        if (busy() || current.state() != ComboRun.State.READY) throw new IllegalStateException("This Combo event cannot start.");
        if (contender.getVoteManager().isVoteActive()) throw new IllegalStateException("Wait for the vote to finish.");
        if (contender.getLobbyManager().getLobbyLocation() == null) throw new IllegalStateException("Set a lobby with /setlobby first.");
        requireSwordKit(current.kitId());
        if (current.allDone()) throw new IllegalStateException("There are no players left to start.");
        var lease = contender.getArenaManager().acquire(current.mapId());
        if (lease == null) throw new IllegalStateException("No arena copies are ready. Rebuild the map copies first.");
        session = new ComboSession(contender, this, current, lease);
        try { session.enable(); } catch (RuntimeException | Error failure) { failed(failure); throw failure; }
    }
    boolean available(ComboRun.Entry entry) {
        Player player = Bukkit.getPlayer(entry.id());
        return player != null && !player.isDead() && contender.getRoleManager().isContestant(entry.id())
                && contender.getDuelManager().getDuel(entry.id()) == null
                && (session != null && session.watching(entry.id()) || !contender.getMinigameManager().pendingReturn(entry.id()));
    }
    me.advait.contender.kit.Kit requireSwordKit(String kitId) {
        var kit = contender.getKitManager().getKit(kitId);
        if (kit == null) throw new IllegalArgumentException("Choose a saved kit.");
        if (kit.isNoClear()) throw new IllegalArgumentException("Choose a saved sword kit with No Clear disabled.");
        if (Arrays.stream(kit.getContents()).noneMatch(item -> item != null && item.getType().name().endsWith("_SWORD")))
            throw new IllegalArgumentException("Choose a kit with a sword for Combo.");
        return kit;
    }
    void capture(Player player) { returns.capture(player); }
    public void save() { store.save(current); }
    private void safeSave() { try { save(); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not save Combo results", failure); } }
    void refresh() { if (contender.getMinigameManager() != null) contender.getMinigameManager().refresh(); }
    @Override public boolean leaveSpectating(Player player) {
        return session != null && session.unwatch(player);
    }
    public void watch(Player player) {
        if (session == null) throw new IllegalStateException("Start Combo before spectating.");
        session.watch(player);
    }
    public void end(ComboRun.State state) {
        if (current == null || current.terminal()) return;
        current.end(state); safeSave();
        ComboSession old = session; session = null;
        if (old != null) {
            old.disable(); resetting = true;
            if (Bukkit.getOnlinePlayers().stream().anyMatch(p -> old.contains(p.getLocation()))) {
                contender.getArenaManager().discard(old.lease()); stranded.put(old.lease(), old); resetting = false;
            } else contender.getArenaManager().reset(old.lease()).whenComplete((ignored, failure) -> {
                if (failure != null) { contender.getArenaManager().discard(old.lease()); contender.getLogger().log(Level.SEVERE, "Could not reset Combo arena", failure); }
                contender.getArenaManager().release(old.lease()); resetting = false;
            });
        }
        refresh();
    }
    @Override public void withdraw(UUID id) {
        if (session != null && session.owns(id)) session.withdraw(id);
        else if (current != null && !current.terminal()) { current.withdraw(id); save(); }
        if (current != null && !current.terminal() && current.allDone()) end(ComboRun.State.FINISHED);
        refresh();
    }
    void failed(Throwable failure) {
        contender.getLogger().log(Level.SEVERE, "Combo stopped", failure);
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("contender.master")).forEach(p -> Dialogs.error(p, "Combo stopped: " + message(failure)));
        end(ComboRun.State.INTERRUPTED);
    }
    public static String message(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
