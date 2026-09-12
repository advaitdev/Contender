package me.advait.contender.manhunt;

import me.advait.contender.Contender;
import me.advait.contender.dialog.ManhuntDialogs;
import me.advait.contender.dialog.Dialogs;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.minigame.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Owns preparation and saved results. The live session owns participant listeners and countdowns. */
public final class ManhuntManager extends AbstractGameState implements MinigameMode {
    private final Contender contender;
    private final ManhuntStore store;
    private final PlayerReturnStore returns;
    private final ManhuntWorld endWorld;
    private final ManhuntDialogs dialogs;
    private ManhuntRun current;
    private ManhuntSession session;

    public ManhuntManager(Contender plugin) {
        super(plugin); contender = plugin; store = new ManhuntStore(plugin.getDataFolder());
        returns = new PlayerReturnStore(plugin, "manhunt-returns.yml"); endWorld = new ManhuntWorld(plugin); dialogs = new ManhuntDialogs(plugin);
    }
    @Override protected void onEnable() {
        try { current = store.load(); if (current != null) save(); }
        catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not read Manhunt results", failure); }
        try { endWorld.reload(this::isEnabled).whenComplete((ignored, error) -> { if (error != null) contender.getLogger().log(Level.SEVERE, "Could not load the prepared Manhunt End", error); }); }
        catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not load the prepared Manhunt End", failure); }
        runRepeating(endWorld::freezeTick, 1, 1);
        runRepeating(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) if (!owns(player.getUniqueId()) && returns.pending(player.getUniqueId()) && !player.isDead()) {
                try { returns.restore(player); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not restore Manhunt player " + player.getName(), failure); }
            }
        }, 20, 20);
    }
    @Override protected void onDisable() {
        if (session != null) finish(ManhuntRun.State.INTERRUPTED, false);
        endWorld.close();
    }
    @Override public String id() { return "manhunt"; }
    @Override public String displayName() { return "Manhunt"; }
    @Override public UUID eventId() { return current == null ? null : current.id(); }
    @Override public String eventName() { return current == null ? "Manhunt" : current.name(); }
    @Override public String statusText() { return current == null ? "No Game" : current.statusText(); }
    @Override public boolean terminal() { return current == null || current.terminal(); }
    @Override public boolean cancelled() { return current != null && (current.state() == ManhuntRun.State.CANCELLED || current.state() == ManhuntRun.State.INTERRUPTED); }
    @Override public boolean active() { return session != null; }
    @Override public boolean busy() { return session != null || endWorld.state() == ManhuntWorld.State.PREPARING; }
    @Override public boolean isReserved(UUID id) { return current != null && !current.terminal() && current.entry(id) != null; }
    @Override public boolean owns(UUID id) { return session != null && session.owns(id); }
    @Override public boolean isPlaying(UUID id) { return session != null && session.playing(id); }
    @Override public boolean isSpectator(UUID id) { return owns(id) && !current.alive(id); }
    @Override public boolean pendingReturn(UUID id) { return returns.pending(id); }
    @Override public boolean inArena(Location location) { return endWorld.contains(location); }
    @Override public boolean restore(Player player) { return returns.restore(player); }
    @Override public void open(Player player) { dialogs.open(player); }
    @Override public void createDialog(Player player) { dialogs.create(player); }
    @Override public void setupDialog(Player player) { dialogs.setup(player); }
    public ManhuntRun current() { return current; }
    public String worldName() { return endWorld.name(); }
    public boolean worldReady() { return endWorld.ready(); }
    public String worldStatus() { return switch (endWorld.state()) { case EMPTY -> "Not Prepared"; case PREPARING -> "Preparing"; case READY -> "Ready · " + endWorld.pillarCount() + " Pillars"; case USED -> "Used · Prepare a Fresh End"; case FAILED -> "Preparation Failed"; }; }
    public CompletableFuture<Void> prepareWorld() {
        if (active() || current != null && !current.terminal()) throw new IllegalStateException("Finish or cancel the current Manhunt first.");
        return endWorld.prepare(this::isEnabled);
    }
    public void create(String name, String kitId, List<ManhuntRun.Entry> entries, int countdown) {
        contender.getMinigameManager().beforeCreate(id());
        if (!terminal() || active()) throw new IllegalStateException("Finish or cancel the current Manhunt first.");
        if (!endWorld.ready()) throw new IllegalStateException("Prepare the End in Manhunt Setup first.");
        if (contender.getKitManager().getKit(kitId) == null) throw new IllegalArgumentException("Choose a saved kit.");
        for (var entry : entries) {
            contender.getDuelManager().requireEligible(entry.id());
            if (contender.getMinigameManager().pendingReturn(entry.id())) throw new IllegalStateException("Return " + entry.name() + " to the lobby first.");
        }
        var next = new ManhuntRun(UUID.randomUUID(), name, kitId, endWorld.name(), entries, countdown);
        store.save(next); current = next; contender.getMinigameManager().select(id()); refresh();
    }
    public void requireCurrent(UUID id) { if (current == null || !current.id().equals(id)) throw new IllegalStateException("That Manhunt is no longer selected."); }
    public void start(UUID id) {
        requireCurrent(id); contender.getMinigameManager().beforeStart(this.id());
        if (active() || current.state() != ManhuntRun.State.READY) throw new IllegalStateException("This Manhunt cannot be started.");
        if (!endWorld.ready() || !current.worldName().equals(endWorld.name())) throw new IllegalStateException("Prepare a fresh End and create the Manhunt again.");
        if (contender.getLobbyManager().getLobbyLocation() == null) throw new IllegalStateException("Set a lobby with /setlobby first.");
        if (current.alive(ManhuntRun.Team.RUNNER) == 0 || current.alive(ManhuntRun.Team.HUNTER) == 0) throw new IllegalStateException("Each team needs at least one player to start.");
        for (var entry : current.entries()) if (current.alive(entry.id())) requireAvailable(entry);
        var kit = contender.getKitManager().getKit(current.kitId());
        if (kit == null) throw new IllegalStateException("The saved kit was removed. Create the Manhunt with another kit.");
        // Capture the whole roster before moving or changing anyone.
        try {
            for (var entry : current.entries()) if (current.alive(entry.id())) returns.capture(Objects.requireNonNull(Bukkit.getPlayer(entry.id())));
            endWorld.use(); session = new ManhuntSession(contender, this, current, endWorld, kit); session.enable();
        } catch (RuntimeException | Error failure) { failed(failure); throw failure; }
        refresh();
    }
    private void requireAvailable(ManhuntRun.Entry entry) {
        var player = Bukkit.getPlayer(entry.id());
        if (player == null || player.isDead()) throw new IllegalStateException(entry.name() + " must be online and alive to start.");
        contender.getDuelManager().requireEligible(entry.id());
        if (contender.getDuelManager().getDuel(entry.id()) != null || contender.getMinigameManager().owns(entry.id())) throw new IllegalStateException(entry.name() + " must leave their current game first.");
        if (contender.getMinigameManager().pendingReturn(entry.id())) throw new IllegalStateException("Return " + entry.name() + " to the lobby first.");
    }
    public void cancel(UUID id) { requireCurrent(id); finish(ManhuntRun.State.CANCELLED, false); }
    @Override public void forceCancel() {
        ManhuntSession previous = session;
        session = null;
        if (current != null) current.cancel();
        List<Throwable> failures = new ArrayList<>();
        cleanup(failures, "Could not save cancelled Manhunt", () -> { if (current != null) save(); });
        cleanup(failures, "Could not stop End preparation", endWorld::cancelPreparation);
        if (previous != null) cleanup(failures, "Could not close Manhunt session", previous::disable);
        restorePending();
        cleanup(failures, "Could not return Manhunt observers", this::returnObservers);
        cleanup(failures, "Could not freeze the Manhunt End", endWorld::freeze);
        cleanup(failures, "Could not refresh Manhunt display", this::refresh);
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Manhunt stopped, but some cleanup failed. Check the server log.");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }
    private void cleanup(List<Throwable> failures, String message, Runnable action) {
        try { action.run(); }
        catch (RuntimeException | Error failure) { failures.add(failure); contender.getLogger().log(Level.SEVERE, message, failure); }
    }
    private void restorePending() {
        for (Player player : Bukkit.getOnlinePlayers()) if (!owns(player.getUniqueId()) && returns.pending(player.getUniqueId())) {
            try { returns.restore(player); }
            catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not restore Manhunt player " + player.getName(), failure); }
        }
    }
    private void returnObservers() {
        var lobby = contender.getLobbyManager().getLobbyLocation();
        if (lobby == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) if (!player.isDead() && inArena(player.getLocation()) && !returns.pending(player.getUniqueId())) {
            try { player.teleport(lobby); }
            catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not return Manhunt observer " + player.getName(), failure); }
        }
    }
    void completed() { finish(current.state(), true); }
    void finish(ManhuntRun.State result, boolean announce) {
        if (current == null && session == null) return;
        if (current != null) current.end(result);
        try { if (current != null) save(); } catch (RuntimeException failure) { contender.getLogger().log(Level.SEVERE, "Could not save Manhunt result", failure); }
        ManhuntSession previous = session; session = null;
        if (previous != null) try { previous.disable(); }
        catch (RuntimeException | Error failure) { contender.getLogger().log(Level.SEVERE, "Could not close Manhunt session", failure); }
        restorePending();
        if (endWorld.state() == ManhuntWorld.State.USED) endWorld.freeze();
        if (announce && current != null) Bukkit.broadcast(Component.text(current.state() == ManhuntRun.State.RUNNERS_WON ? "The runners win!" : "The hunters win!", NamedTextColor.GREEN));
        refresh();
    }
    void failed(Throwable failure) {
        contender.getLogger().log(Level.SEVERE, "Manhunt stopped", failure);
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("contender.master")).forEach(p -> Dialogs.error(p, "Manhunt stopped: " + failure.getMessage()));
        finish(ManhuntRun.State.INTERRUPTED, false);
    }
    @Override public void withdraw(UUID id) {
        if (current == null || current.entry(id) == null || current.terminal()) return;
        if (session != null) session.withdraw(id);
        else { current.eliminate(id); save(); refresh(); }
    }
    void save() { store.save(current); }
    void refresh() {
        if (contender.getMinigameManager() != null) contender.getMinigameManager().refresh();
        contender.refreshVoiceRouting(); contender.refreshHackerAttributes();
    }
    @Override public List<MinigameStanding> standings() {
        if (current == null) return List.of();
        return current.entries().stream().sorted(Comparator.comparing(ManhuntRun.Entry::team).thenComparing(e -> !current.alive(e.id())))
                .map(e -> new MinigameStanding(e.id(), e.name(), (e.team() == ManhuntRun.Team.RUNNER ? "Runner" : "Hunter") + (current.alive(e.id()) ? "" : " · Out"), current.alive(e.id()) ? (e.team() == ManhuntRun.Team.RUNNER ? NamedTextColor.GREEN : NamedTextColor.RED) : NamedTextColor.GRAY)).toList();
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void freezeDamage(EntityDamageEvent event) { if (endWorld.frozen() && inArena(event.getEntity().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) { if (inArena(event.getLocation())) endWorld.freezeEntity(event.getEntity()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breaking(BlockBreakEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) { if (endWorld.frozen() && inArena(event.getPlayer().getLocation())) { event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void physics(BlockPhysicsEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flow(BlockFromToEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explosion(EntityExplodeEvent event) { if (endWorld.frozen() && inArena(event.getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explosion(BlockExplodeEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent event) { if (endWorld.frozen() && inArena(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void join(PlayerJoinEvent event) { runLater(() -> { if (!owns(event.getPlayer().getUniqueId())) returns.restore(event.getPlayer()); }, 1); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event) {
        if (returns.pending(event.getPlayer().getUniqueId()) && !owns(event.getPlayer().getUniqueId())) {
            var lobby = contender.getLobbyManager().getLobbyLocation(); if (lobby != null) event.setRespawnLocation(lobby);
            runLater(() -> returns.restore(event.getPlayer()), 1);
        }
    }
}
