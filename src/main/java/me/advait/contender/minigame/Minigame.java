package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.core.Tasks;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.spectate.Spectatable;
import me.advait.contender.stage.Stage;
import me.advait.contender.tab.BracketLayout;
import me.advait.contender.tab.StandingsLayout;
import me.advait.contender.util.YamlStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Shared plumbing for every minigame: the roster, claiming and restoring players, the countdown,
 * spectators, the leaderboard, and saving results. A game only describes its own rules:
 * {@link #prepare()}, {@link #setup(List)}, {@link #begin()}, {@link #tick()} and {@link #standings()}.
 *
 * Lifecycle: READY → PREPARING → COUNTDOWN → RUNNING → FINISHED, or CANCELLED at any point.
 * Players stay claimed (and in the game's area) until the game closes; eliminated players watch in
 * Spectator mode. Everyone gets their own inventory back in the lobby when it ends.
 */
public abstract class Minigame implements Stage, Activity, Spectatable, Listener {
    public enum State { READY, PREPARING, COUNTDOWN, RUNNING, FINISHED, CANCELLED }
    public enum Status { WAITING, PLAYING, OUT, DONE, WITHDRAWN }

    /** One entered player. {@code score} orders the leaderboard; {@code value} is what it shows. */
    public static final class Participant {
        public final UUID id;
        public final String name;
        public Status status = Status.WAITING;
        public double score;
        public String value = "";
        public int place;

        public Participant(UUID id, String name) { this.id = id; this.name = name; }
    }

    protected final Contender plugin;
    protected final MinigameType type;
    protected final UUID id;
    protected final String name;
    protected final Map<UUID, Participant> roster = new LinkedHashMap<>();
    protected final Set<UUID> watchers = new LinkedHashSet<>();
    protected Tasks tasks;
    protected State state = State.READY;
    private boolean listening;
    private boolean closed = true;
    private int places;

    protected Minigame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players) {
        this.plugin = plugin;
        this.type = type;
        this.id = id;
        this.name = name;
        players.forEach((player, playerName) -> roster.put(player, new Participant(player, playerName)));
    }

    // ---- What each game provides ---------------------------------------------------------------

    /** Load worlds, lease arenas, spawn course mobs. Runs before anyone is moved. */
    protected abstract CompletableFuture<Void> prepare();

    /** Move and equip players. Throwing aborts the start and returns everyone. */
    protected abstract void setup(List<Player> players);

    /** The countdown ended; play starts. */
    protected abstract void begin();

    /** Once per second while running. */
    protected void tick() { }

    /** Leaderboard rows, best first. */
    protected abstract List<StandingsLayout.Row> standings();

    /** Remove spawned entities and release arenas. Called once when the game closes. */
    protected void cleanup() { }

    /** Called when a playing participant disconnects. Default: they are out. */
    protected void playerLeft(Participant participant) { participant.status = Status.OUT; checkEnd(); }

    /** Called when a claimed participant comes back while the game runs. Default: watch from the spectator spawn. */
    protected void playerReturned(Player player, Participant participant) {
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(spectatorSpawn());
    }

    /** Ends the game when its win condition is met. Default: one or no players left. */
    protected void checkEnd() {
        if (state == State.RUNNING && playing().size() <= 1) finish();
    }

    protected int minimumPlayers() { return 1; }

    protected int countdownSeconds() { return 10; }

    protected void writeExtra(ConfigurationSection section) { }

    // ---- Stage ---------------------------------------------------------------------------------

    @Override public UUID id() { return id; }
    @Override public String name() { return name; }
    @Override public String kind() { return type.name(); }
    @Override public boolean started() { return state.ordinal() >= State.COUNTDOWN.ordinal(); }
    @Override public boolean finished() { return state == State.FINISHED || state == State.CANCELLED; }
    @Override public boolean cancelled() { return state == State.CANCELLED; }
    @Override public boolean involves(UUID player) {
        Participant participant = roster.get(player);
        if (participant == null || participant.status == Status.WITHDRAWN) return false;
        // Once the game is under way, someone who went back to the lobby is no longer part of it.
        return !started() || finished() || plugin.getRegistry().owner(player) == this || Bukkit.getPlayer(player) == null;
    }
    @Override public String statusText() {
        return switch (state) {
            case READY -> "Ready"; case PREPARING -> "Preparing"; case COUNTDOWN -> "Starting";
            case RUNNING -> "Live"; case FINISHED -> "Finished"; case CANCELLED -> "Cancelled";
        };
    }
    @Override public BracketLayout.Layout layout(BracketLayout.View view, Function<UUID, BracketLayout.Presence> presence, List<UUID> others) {
        return StandingsLayout.render(name, standings(), id -> presence.apply(id), plugin.getThemes().current());
    }
    @Override public String caption(BracketLayout.Layout layout) { return type.name(); }

    public MinigameType type() { return type; }
    public State state() { return state; }
    public Collection<Participant> roster() { return Collections.unmodifiableCollection(roster.values()); }
    public Participant participant(UUID player) { return roster.get(player); }
    public boolean isPlaying(UUID player) { Participant p = roster.get(player); return p != null && p.status == Status.PLAYING; }

    /** Online participants still in play. */
    public List<Player> playing() {
        List<Player> players = new ArrayList<>();
        for (Participant participant : roster.values()) {
            if (participant.status != Status.PLAYING) continue;
            Player player = Bukkit.getPlayer(participant.id);
            if (player != null) players.add(player);
        }
        return players;
    }

    // ---- Activity ------------------------------------------------------------------------------

    @Override public String displayName() { return name; }

    @Override public void handleQuit(Player player) {
        Participant participant = roster.get(player.getUniqueId());
        // Everyone leaves when the server stops; that's a cancellation, not a result.
        if (participant == null || Bukkit.isStopping()) return;
        if (state == State.RUNNING && participant.status == Status.PLAYING) {
            broadcast(plugin.getNameTagManager().displayName(player).append(Msg.text(" left the game.", DialogPalette.WARNING)));
            playerLeft(participant);
        }
        // During the countdown nothing changes yet: they can rejoin, or the game handles it when play starts.
    }

    @Override public void handleJoin(Player player) {
        Participant participant = roster.get(player.getUniqueId());
        if (participant == null) return;
        if (finished() || closed) {
            plugin.getRegistry().release(player.getUniqueId(), this);
            plugin.getSnapshots().restoreToLobby(player);
            return;
        }
        // Back before the countdown ended: they're still where they spawned.
        if (state == State.COUNTDOWN && participant.status == Status.PLAYING) return;
        playerReturned(player, participant);
    }

    @Override public void forceStop(String reason) { cancel(reason); }

    // ---- Spectatable ---------------------------------------------------------------------------

    @Override public boolean acceptsWatchers() { return state == State.COUNTDOWN || state == State.RUNNING; }
    @Override public void addWatcher(UUID player) { watchers.add(player); }
    @Override public void removeWatcher(UUID player) { watchers.remove(player); }
    @Override public Set<UUID> players() {
        Set<UUID> ids = new HashSet<>();
        for (Participant participant : roster.values()) if (participant.status == Status.PLAYING) ids.add(participant.id);
        return ids;
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    /** Checks the roster, prepares the game, then moves everyone in and counts down. */
    public void start() {
        if (state != State.READY) throw new IllegalStateException(name + " can't be started again. Create a new game.");
        if (plugin.getStages().current() != this) throw new IllegalStateException("This game is no longer selected.");
        if (plugin.getVotes().isActive()) throw new IllegalStateException("Wait for the vote to finish.");
        List<Player> players = new ArrayList<>();
        for (Participant participant : roster.values()) {
            if (participant.status == Status.WITHDRAWN) continue;
            Player player = Bukkit.getPlayer(participant.id);
            if (player == null) throw new IllegalStateException(participant.name + " is offline. Withdraw them or wait for them to join.");
            if (plugin.getRoleManager().getRole(participant.id) != PlayerRole.CONTESTANT) throw new IllegalStateException(participant.name + " is not a contestant.");
            if (plugin.getRegistry().isPlaying(participant.id)) {
                throw new IllegalStateException(participant.name + " is busy in " + plugin.getRegistry().owner(participant.id).displayName() + ".");
            }
            if (player.isDead()) throw new IllegalStateException(participant.name + " must respawn first.");
            players.add(player);
        }
        if (players.size() < minimumPlayers()) throw new IllegalStateException("This game needs at least " + minimumPlayers() + " players.");
        tasks = new Tasks(plugin, name);
        closed = false;
        state = State.PREPARING;
        plugin.getStages().refreshDisplays();
        CompletableFuture<Void> preparing;
        try { preparing = prepare(); }
        catch (RuntimeException failure) { preparing = CompletableFuture.failedFuture(failure); }
        preparing.whenComplete((ignored, failure) -> {
            if (state != State.PREPARING) return;
            if (failure != null) { abortStart(failure); return; }
            try { enter(players); }
            catch (RuntimeException error) { abortStart(error); }
        });
    }

    private void abortStart(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        // Setup problems (missing spawns, a course being edited) are expected; only real errors get a stack trace.
        if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
            plugin.getLogger().warning(name + " could not start: " + cause.getMessage());
        } else plugin.getLogger().log(Level.WARNING, name + " could not start", failure);
        for (Participant participant : roster.values()) {
            if (plugin.getRegistry().owner(participant.id) != this) continue;
            plugin.getRegistry().release(participant.id, this);
            Player player = Bukkit.getPlayer(participant.id);
            if (player != null) plugin.getSnapshots().restoreToLobby(player);
            if (participant.status == Status.PLAYING) participant.status = Status.WAITING;
        }
        stopListening();
        try { cleanup(); } catch (RuntimeException error) { plugin.getLogger().log(Level.WARNING, name + " cleanup failed", error); }
        if (tasks != null) tasks.close();
        closed = true;
        state = State.READY;
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("contender.master")) Msg.error(admin, name + " could not start: " + Msg.reason(failure));
        }
        plugin.getStages().refreshDisplays();
    }

    private void enter(List<Player> players) {
        List<Player> present = new ArrayList<>();
        for (Player player : players) {
            if (!player.isOnline() || player.isDead()) continue;
            // Withdrawn while the arena or course was being prepared.
            if (roster.get(player.getUniqueId()).status == Status.WITHDRAWN) continue;
            if (plugin.getRegistry().isWatching(player.getUniqueId())) plugin.getSpectate().stop(player.getUniqueId(), false);
            plugin.getRegistry().claim(player.getUniqueId(), this, ActivityRegistry.Involvement.PLAYING);
            plugin.getSnapshots().capture(player);
            roster.get(player.getUniqueId()).status = Status.PLAYING;
            present.add(player);
        }
        if (present.size() < minimumPlayers()) throw new IllegalStateException("Not enough players are online to start.");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listening = true;
        setup(present);
        plugin.getHackers().refresh();
        state = State.COUNTDOWN;
        save();
        plugin.getStages().refreshDisplays();
        countdown(countdownSeconds(), () -> {
            state = State.RUNNING;
            // Anyone who disconnected during the countdown is handled by the game's own rules now.
            for (Participant participant : List.copyOf(roster.values())) {
                if (participant.status == Status.PLAYING && Bukkit.getPlayer(participant.id) == null) playerLeft(participant);
            }
            if (state != State.RUNNING) return;
            plugin.getStages().started(this);
            Set<UUID> audience = audience();
            Msg.actionBar(audience, Component.empty());
            Msg.title(audience, Component.text("Go!", plugin.getThemes().current().primary()), Component.empty(), 0, 16, 6);
            Sounds.GO.play(audience);
            begin();
            if (state != State.RUNNING) return;
            checkEnd();
            if (state != State.RUNNING) return;
            tasks.repeat(20, 20, () -> {
                if (state != State.RUNNING) return;
                tick();
                plugin.getStages().refreshDisplays();
            });
            save();
        });
    }

    /** Shows a countdown on the action bar, with big numbers for the last three seconds. */
    protected org.bukkit.scheduler.BukkitTask countdown(int seconds, Runnable then) {
        int[] left = {seconds};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = tasks.repeat(0, 20, () -> {
            int now = left[0]--;
            Set<UUID> audience = audience();
            if (now <= 0) { tasks.cancel(task[0]); then.run(); return; }
            if (now <= 3) {
                Msg.title(audience, Component.text(Integer.toString(now), plugin.getThemes().current().primary()), Component.empty(), 0, 22, 2);
                Sounds.TICK_HIGH.play(audience);
            } else {
                Msg.actionBar(audience, Msg.text(name + " starts in ", DialogPalette.MUTED).append(Msg.text(now + "s", DialogPalette.ACCENT)));
                if (now <= 5) Sounds.TICK.play(audience);
            }
        });
        return task[0];
    }

    /** Ends the game normally: shows the results, then returns everyone after a short celebration. */
    public void finish() {
        if (finished()) return;
        state = State.FINISHED;
        tasks.cancelAll();
        announceResults();
        save();
        plugin.getStages().ended(this);
        tasks.later(120, this::close);
    }

    /** Stops immediately without a celebration. Results so far are kept. */
    public void cancel(String reason) {
        if (state == State.CANCELLED && closed) return;
        // Already over: just skip the celebration and keep the result.
        if (state == State.FINISHED) {
            if (tasks != null) tasks.cancelAll();
            close();
            return;
        }
        boolean wasLive = !closed;
        state = State.CANCELLED;
        if (tasks != null) tasks.cancelAll();
        if (reason != null && !reason.isBlank() && wasLive) broadcast(Msg.text(reason, DialogPalette.WARNING));
        close();
        save();
        plugin.getStages().ended(this);
    }

    private void close() {
        if (closed) return;
        closed = true;
        stopListening();
        try { cleanup(); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.SEVERE, name + " cleanup failed", failure); }
        for (UUID watcher : List.copyOf(watchers)) plugin.getSpectate().stop(watcher, true);
        watchers.clear();
        for (Participant participant : roster.values()) {
            if (plugin.getRegistry().owner(participant.id) != this) continue;
            plugin.getRegistry().release(participant.id, this);
            Player player = Bukkit.getPlayer(participant.id);
            if (player == null || player.isDead()) continue;
            player.sendActionBar(Component.empty());
            plugin.getSnapshots().restoreToLobby(player);
        }
        if (tasks != null) tasks.close();
        plugin.getHackers().refresh();
        plugin.getStages().refreshDisplays();
    }

    private void stopListening() {
        if (listening) HandlerList.unregisterAll(this);
        listening = false;
    }

    // ---- Helpers for games ---------------------------------------------------------------------

    /** Players and watchers: everyone who should see this game's messages. */
    protected Set<UUID> audience() {
        Set<UUID> audience = new LinkedHashSet<>();
        for (Participant participant : roster.values()) if (plugin.getRegistry().owner(participant.id) == this) audience.add(participant.id);
        audience.addAll(watchers);
        return audience;
    }

    protected void broadcast(Component message) { Msg.send(audience(), message); }

    /** Records the next finishing place (1st, 2nd …) for a participant. */
    protected int nextPlace() { return ++places; }

    /** Takes a player out of play; they keep watching in Spectator mode. */
    protected void eliminate(Player player, Component message) {
        Participant participant = roster.get(player.getUniqueId());
        if (participant == null || participant.status != Status.PLAYING) return;
        participant.status = Status.OUT;
        plugin.getDeathEffect().play(player);
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        player.setVelocity(new Vector(0, 0.3, 0));
        if (message != null) broadcast(message);
        Sounds.ELIMINATED.play(audience());
        Msg.title(player, Msg.text("Out", DialogPalette.DANGER), Msg.text("You can keep watching", DialogPalette.MUTED), 2, 30, 8);
        checkEnd();
    }

    /** Removes a player from the game and sends them back to the lobby. */
    public void withdraw(UUID player) {
        Participant participant = roster.get(player);
        if (participant == null || finished()) return;
        // Someone who is already out (or done) keeps their result; they're only leaving to the lobby.
        if (participant.status == Status.PLAYING || participant.status == Status.WAITING) participant.status = Status.WITHDRAWN;
        if (plugin.getRegistry().owner(player) == this) {
            plugin.getRegistry().release(player, this);
            Player online = Bukkit.getPlayer(player);
            if (online != null) plugin.getSnapshots().restoreToLobby(online);
        }
        save();
        plugin.getStages().refreshDisplays();
        if (state == State.RUNNING) checkEnd();
    }

    /** Top three in chat and a title for everyone. */
    protected void announceResults() {
        List<StandingsLayout.Row> rows = standings();
        var theme = plugin.getThemes().current();
        Msg.broadcast(Msg.text(name + " is over.", DialogPalette.ACCENT));
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            var row = rows.get(i);
            Msg.broadcast(Msg.text("  " + (i + 1) + ". ", DialogPalette.MUTED).append(Msg.text(nameOf(row.player(), row.fallbackName()), DialogPalette.TEXT))
                    .append(Msg.text("  " + row.value(), DialogPalette.ACCENT)));
        }
        if (rows.isEmpty()) return;
        String winner = nameOf(rows.getFirst().player(), rows.getFirst().fallbackName());
        for (Player player : Bukkit.getOnlinePlayers()) {
            Msg.title(player, Component.text(winner, theme.primary()), Msg.text("wins " + name, DialogPalette.MUTED), 8, 70, 16);
            Sounds.VICTORY.play(player);
        }
        Player champion = Bukkit.getPlayer(rows.getFirst().player());
        if (champion != null) plugin.getCelebrations().fireworks(champion.getLocation(), 4);
    }

    protected String nameOf(UUID id, String fallback) {
        Participant participant = roster.get(id);
        if (participant != null) return participant.name;
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? fallback : name;
    }

    // ---- Persistence ---------------------------------------------------------------------------

    /** Results are saved so the board keeps showing them after a restart. */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", id.toString());
        yaml.set("name", name);
        yaml.set("state", state.name());
        for (Participant participant : roster.values()) {
            String path = "roster." + participant.id;
            yaml.set(path + ".name", participant.name);
            yaml.set(path + ".status", participant.status.name());
            yaml.set(path + ".score", participant.score);
            yaml.set(path + ".value", participant.value);
            yaml.set(path + ".place", participant.place);
        }
        writeExtra(yaml.createSection("extra"));
        try { YamlStorage.save(yaml, file(plugin, type.id())); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.WARNING, "Could not save " + name, failure); }
    }

    public static File file(Contender plugin, String typeId) {
        return new File(new File(plugin.getDataFolder(), "minigames"), typeId + ".yml");
    }

    /** Restores names, statuses and scores. A game that was running when the server stopped counts as cancelled. */
    public void readSaved(YamlConfiguration yaml) {
        ConfigurationSection saved = yaml.getConfigurationSection("roster");
        if (saved != null) for (String key : saved.getKeys(false)) {
            Participant participant = roster.get(UUID.fromString(key));
            if (participant == null) continue;
            try { participant.status = Status.valueOf(saved.getString(key + ".status", "WAITING")); }
            catch (IllegalArgumentException ignored) { }
            participant.score = saved.getDouble(key + ".score");
            participant.value = saved.getString(key + ".value", "");
            participant.place = saved.getInt(key + ".place");
        }
        State savedState;
        try { savedState = State.valueOf(yaml.getString("state", "READY")); }
        catch (IllegalArgumentException invalid) { savedState = State.READY; }
        state = switch (savedState) {
            case READY, PREPARING -> State.READY;
            case FINISHED -> State.FINISHED;
            default -> State.CANCELLED;
        };
        if (state == State.READY) for (Participant participant : roster.values()) {
            if (participant.status != Status.WITHDRAWN) participant.status = Status.WAITING;
        }
    }

    public static Map<UUID, String> savedRoster(YamlConfiguration yaml) {
        Map<UUID, String> players = new LinkedHashMap<>();
        ConfigurationSection saved = yaml.getConfigurationSection("roster");
        if (saved != null) for (String key : saved.getKeys(false)) {
            try { players.put(UUID.fromString(key), saved.getString(key + ".name", key)); }
            catch (IllegalArgumentException ignored) { }
        }
        return players;
    }
}
