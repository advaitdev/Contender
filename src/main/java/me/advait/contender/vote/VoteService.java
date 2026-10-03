package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.*;

/**
 * Community votes: countdown timers that turn into the results, optional voting and judge rooms, and a
 * reveal ceremony at the end. Votes never change roles unless the director asks.
 */
public final class VoteService extends Module {
    /** The two places players can be sent while a vote runs. */
    public enum Room {
        VOTING("voting", "voting room"), JUDGE("judges", "judge room");
        final String key;
        public final String label;
        Room(String key, String label) { this.key = key; this.label = label; }
    }

    /** What happens to players when a vote starts, and after its results. */
    public enum RoomMode {
        RETURN("Move, Then Send Back"), STAY("Move and Leave There"), OFF("Don't Move Anyone");
        public final String label;
        RoomMode(String label) { this.label = label; }
    }

    /** What happens to the player who was voted out. */
    public enum Elimination {
        KILL("Dies, then spectates"), SPECTATE("Becomes a spectator"), KEEP("Stays a contestant");
        public final String label;
        Elimination(String label) { this.label = label; }
    }

    /**
     * @param liveCounts show how many votes each player has in /vote while voting
     * @param anonymous  false shows, above each voter, who they voted for once the results are out
     * @param safe       contestants who vote but can't be voted for
     * @param sittingOut contestants left out of the vote completely
     * @param skipping   whether players can vote Skip, and whether most skips saves everyone
     */
    public record Options(int seconds, boolean liveCounts, boolean ceremony, Elimination elimination, RoomMode rooms, boolean anonymous,
                          Set<UUID> safe, Set<UUID> sittingOut, VoteSession.Skipping skipping) {
        public Options {
            safe = Set.copyOf(safe);
            sittingOut = Set.copyOf(sittingOut);
            java.util.Objects.requireNonNull(skipping);
        }
        public Options(int seconds, boolean liveCounts, boolean ceremony, Elimination elimination, RoomMode rooms, boolean anonymous,
                       Set<UUID> safe, Set<UUID> sittingOut) {
            this(seconds, liveCounts, ceremony, elimination, rooms, anonymous, safe, sittingOut, VoteSession.Skipping.SAVES);
        }
        public Options(int seconds, boolean liveCounts, boolean ceremony, Elimination elimination, RoomMode rooms, boolean anonymous) {
            this(seconds, liveCounts, ceremony, elimination, rooms, anonymous, Set.of(), Set.of());
        }
        public Options(int seconds, boolean liveCounts, boolean ceremony, boolean eliminate) {
            this(seconds, liveCounts, ceremony, eliminate ? Elimination.KILL : Elimination.KEEP, RoomMode.RETURN, true);
        }
    }

    /** The results list replaces the timers this long after voting closes. */
    private static final long RESULTS_DELAY_TICKS = 30;
    /** Without a ceremony, how long the results stay up. */
    private static final long QUICK_RESULTS_TICKS = 200;

    private VoteSession session;
    private VoteTimerDisplay timer;
    private VoteRooms rooms;
    private VoteReceipts receipts;
    private VoteReveal reveal;
    private Options options;
    /** Counts votes, so a delayed step from an old vote never touches a newer one. */
    private int generation;
    /** Players voted out by death, and where they fell (they respawn there). */
    private final Map<UUID, Location> voteDeaths = new HashMap<>();

    public VoteService(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        timer = new VoteTimerDisplay(plugin);
        rooms = new VoteRooms(plugin);
        receipts = new VoteReceipts(plugin);
        tasks.repeat(20, 20, this::second);
    }

    @Override protected void onDisable() {
        if (reveal != null) reveal.forceStop("");
        session = null;
        timer.hide();
        receipts.clear();
    }

    public boolean isActive() { return session != null; }
    public VoteSession session() { return session; }
    public boolean revealing() { return reveal != null; }

    public void start(Options chosen) {
        if (session != null) throw new IllegalStateException("A vote is already running.");
        if (reveal != null || receipts.showing()) throw new IllegalStateException("Wait for the results to finish.");
        if (chosen.seconds() < 10 || chosen.seconds() > 3600) throw new IllegalArgumentException("Choose a length from 10 seconds to 60 minutes.");
        List<Map.Entry<UUID, String>> players = new ArrayList<>();
        Map<UUID, String> voters = new LinkedHashMap<>();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (Player player : online) {
            UUID id = player.getUniqueId();
            if (plugin.getRoleManager().getRole(id) != PlayerRole.CONTESTANT || chosen.sittingOut().contains(id)) continue;
            voters.put(id, player.getName());
            if (!chosen.safe().contains(id)) players.add(Map.entry(id, player.getName()));
        }
        if (players.size() < 2) throw new IllegalStateException(chosen.safe().isEmpty() && chosen.sittingOut().isEmpty()
                ? "At least two contestants must be online to vote." : "At least two online contestants must be on the ballot.");
        generation++;
        timer.hide();
        options = chosen;
        session = new VoteSession(VoteSession.number(players), voters, chosen.sittingOut(), chosen.skipping(), chosen.liveCounts(), System.currentTimeMillis() + chosen.seconds() * 1000L);
        if (plugin.getTournaments().current() != null && plugin.getTournaments().current().isRunning()) plugin.getTournaments().pause();
        rooms.gather(chosen.rooms());
        var theme = plugin.getThemes().current();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Msg.title(player, Component.text("Vote", theme.primary()), Msg.text(session.sittingOut(player.getUniqueId())
                    ? "You're sitting out this one" : "Use /vote to choose a player", DialogPalette.MUTED), 8, 50, 12);
            Sounds.ANNOUNCE.play(player);
        }
        Msg.broadcast(Msg.text("A vote has started. ", DialogPalette.ACCENT).append(Msg.text("Use /vote to choose a player.", DialogPalette.TEXT)));
        second();
    }

    private void second() {
        if (session == null) return;
        int left = (int) Math.max(0, Math.ceil((session.endsAt() - System.currentTimeMillis()) / 1000.0));
        timer.update(left);
        Component bar = Msg.text("Vote  ", DialogPalette.ACCENT).append(Msg.text(VoteTimerDisplay.clock(left),
                left <= 10 ? DialogPalette.DANGER : DialogPalette.TEXT));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getRegistry().isPlaying(player.getUniqueId())) continue; // Don't cover a fight's action bar.
            Msg.status(player, bar);
            if (left <= 10 && left > 0) Sounds.TICK.play(player);
        }
        if (left <= 0) end();
    }

    public void vote(Player voter, VoteSession.Candidate target) {
        VoteSession current = require();
        if (plugin.getRoleManager().getRole(voter.getUniqueId()) != PlayerRole.CONTESTANT) throw new IllegalStateException("Only contestants can vote.");
        current.vote(voter.getUniqueId(), target);
        current.addVoter(voter.getUniqueId(), voter.getName());
        Sounds.CLICK.play(voter);
    }

    public void skip(Player voter) {
        VoteSession current = require();
        if (plugin.getRoleManager().getRole(voter.getUniqueId()) != PlayerRole.CONTESTANT) throw new IllegalStateException("Only contestants can vote.");
        current.skip(voter.getUniqueId());
        current.addVoter(voter.getUniqueId(), voter.getName());
        Sounds.CLICK.play(voter);
    }

    public void unvote(Player voter) { require().unvote(voter.getUniqueId()); }

    public void removeCandidate(UUID candidate) { require().remove(candidate); }

    private VoteSession require() {
        if (session == null) throw new IllegalStateException("No vote is running.");
        return session;
    }

    /** Closes voting and plays the results. The timers read 00:00, then show the vote counts. */
    public void end() {
        VoteSession closing = session;
        if (closing == null) return;
        closing.close();
        session = null;
        int current = generation;
        timer.update(0);
        // With a ceremony the timers stay at 00:00 until the spotlight is done (VoteReveal shows the results).
        if (!options.ceremony()) tasks.later(RESULTS_DELAY_TICKS, () -> { if (generation == current) timer.showResults(closing.results(), closing.skips()); });
        for (Player player : Bukkit.getOnlinePlayers()) if (!plugin.getRegistry().isPlaying(player.getUniqueId())) player.sendActionBar(Component.empty());
        // Who voted for whom, when votes aren't anonymous. With a ceremony they wait for the spotlight, so they don't
        // give the result away. The vote isn't over until they're gone.
        if (options.ceremony()) {
            reveal = new VoteReveal(plugin, this, closing, options.elimination(), !options.anonymous());
            reveal.play();
        } else {
            if (!options.anonymous()) receipts.show(closing);
            long overAt = receipts.showing() ? receipts.until() : 0;
            announce(closing);
            List<VoteSession.Candidate> leaders = closing.leaders();
            if (leaders.size() == 1 && !closing.skipped()) voteOut(leaders.getFirst().id(), options.elimination());
            long wait = Math.max(100, (overAt - System.currentTimeMillis()) / 50 + 5);
            tasks.later(wait, () -> { if (session == null && reveal == null) rooms.sendBack(stageLocation()); });
            tasks.later(Math.max(QUICK_RESULTS_TICKS, wait), () -> { if (generation == current && reveal == null) timer.hide(); });
        }
    }

    private void announce(VoteSession closed) {
        List<VoteSession.Candidate> leaders = closed.leaders();
        if (closed.skipped()) {
            Msg.broadcast(Msg.text("Most votes: ", DialogPalette.MUTED).append(Msg.text("Skip", DialogPalette.ACCENT))
                    .append(Msg.text(" (" + closed.skips() + "). Nobody was voted out.", DialogPalette.MUTED)));
            Sounds.ANNOUNCE.playAll();
            return;
        }
        if (leaders.isEmpty()) { Msg.broadcast(Msg.text(closed.skips() > 0 ? "Only skips were cast, so nobody was voted out." : "The vote ended with no votes cast.", DialogPalette.MUTED)); return; }
        int votes = closed.votesFor(leaders.getFirst().id());
        String names = String.join(" & ", leaders.stream().map(VoteSession.Candidate::name).toList());
        Msg.broadcast(Msg.text(leaders.size() > 1 ? "Tied: " : "Most votes: ", DialogPalette.MUTED)
                .append(Msg.text(names, DialogPalette.ACCENT)).append(Msg.text(" (" + votes + ")", DialogPalette.MUTED)));
        Sounds.ANNOUNCE.playAll();
    }

    /**
     * Applies the chosen outcome to the voted-out player. With {@link Elimination#KILL} they die where they stand
     * (keeping their items), respawn on the same spot right away and become a spectator.
     */
    void voteOut(UUID id, Elimination elimination) {
        if (elimination == Elimination.KEEP) return;
        Player player = Bukkit.getPlayer(id);
        if (elimination == Elimination.KILL && player != null && !player.isDead()) {
            voteDeaths.put(id, player.getLocation());
            player.setHealth(0);
            tasks.later(2, () -> {
                if (player.isOnline() && player.isDead()) player.spigot().respawn();
                tasks.later(1, () -> makeSpectator(id));
            });
            return;
        }
        makeSpectator(id);
    }

    private void makeSpectator(UUID id) {
        try { plugin.getRoleManager().setRole(id, PlayerRole.SPECTATOR); }
        catch (RuntimeException failure) { plugin.getLogger().warning("Could not make the voted-out player a spectator: " + failure.getMessage()); }
    }

    /** A vote death drops nothing and has no death message; the vote already announced it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!voteDeaths.containsKey(event.getPlayer().getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location fell = voteDeaths.remove(event.getPlayer().getUniqueId());
        if (fell != null) event.setRespawnLocation(fell);
    }

    void revealFinished(VoteReveal finished) {
        if (reveal != finished) return;
        reveal = null;
        timer.hide();
        rooms.sendBack(stageLocation());
    }

    /** Stops a vote or a reveal without results. */
    public void cancel() {
        generation++;
        if (session != null) { session.close(); session = null; }
        if (reveal != null) reveal.forceStop("");
        reveal = null;
        timer.hide();
        receipts.clear();
        rooms.sendBack(stageLocation());
    }

    /** Turns the timers into the results. */
    void showResults(VoteSession closed) { timer.showResults(closed.results(), closed.skips()); }

    /** Shows who voted for whom; returns when the displays go away (epoch millis). */
    long showReceipts(VoteSession closed) {
        receipts.show(closed);
        return receipts.until();
    }

    /**
     * A director's override of one player's vote, for testing: {@code target} null takes the vote away. Works for
     * any contestant in the vote, online or not.
     */
    public void setVote(UUID voter, UUID target) {
        VoteSession current = require();
        String voterName = current.voters().get(voter);
        if (voterName == null) throw new IllegalArgumentException("That player isn't voting in this one.");
        if (target == null) {
            current.unvote(voter);
        } else if (target.equals(VoteSession.SKIP)) {
            current.skip(voter);
        } else {
            VoteSession.Candidate chosen = current.candidate(target);
            if (chosen == null) throw new IllegalArgumentException("That player isn't in this vote.");
            current.vote(voter, chosen);
        }
        plugin.getLogger().info("A director set " + voterName + "'s vote to "
                + (target == null ? "nobody" : target.equals(VoteSession.SKIP) ? "Skip" : current.candidate(target).name()) + ".");
    }

    // ---- Placement -----------------------------------------------------------------------------

    /** Adds a timer where the player looks; returns true when it hangs on a wall. */
    public boolean placeTimer(Player player) { return timer.place(player, VoteTimerDisplay.DEFAULT_SIZE); }
    /** As above, with a size from 1 to 10. */
    public boolean placeTimer(Player player, float size) { return timer.place(player, size); }
    /** Removes the timer nearest the player; returns false when none is within 12 blocks. */
    public boolean removeNearestTimer(Player player) { return timer.removeNearest(player); }
    public void removeTimers() { timer.removeAll(); }
    public int timerCount() { return timer.count(); }
    public boolean timerPlaced() { return timer.placed(); }

    public void setRoom(Room room, Location location) { rooms.set(room, location); }
    public void clearRoom(Room room) { rooms.clear(room); }
    public Location roomLocation(Room room) { return rooms.location(room); }
    public boolean anyRoom() { return rooms.anySet(); }

    /** The stage's center and the way the director faced when setting it; contestants line up facing that way. */
    public void setStage(Location location) {
        if (plugin.getArenas().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Set the vote stage outside the arena world.");
        var config = plugin.getConfig();
        config.set("votes.stage.world", location.getWorld().getName());
        config.set("votes.stage.x", location.getBlockX() + 0.5);
        config.set("votes.stage.y", location.getY());
        config.set("votes.stage.z", location.getBlockZ() + 0.5);
        config.set("votes.stage.yaw", (double) location.getYaw());
        plugin.saveConfig();
    }

    public void clearStage() {
        plugin.getConfig().set("votes.stage", null);
        plugin.saveConfig();
    }

    public Location stageLocation() {
        var config = plugin.getConfig();
        String name = config.getString("votes.stage.world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) return null;
        return new Location(world, config.getDouble("votes.stage.x"), config.getDouble("votes.stage.y"), config.getDouble("votes.stage.z"),
                (float) config.getDouble("votes.stage.yaw"), 0);
    }

    /** Stages set before facings were saved have none. */
    boolean stageHasFacing() { return plugin.getConfig().contains("votes.stage.yaw"); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (session != null) session.unvote(event.getPlayer().getUniqueId());
        rooms.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (session == null) return;
        Player player = event.getPlayer();
        // After the lobby has placed them, send a late arrival to their room as well.
        tasks.later(5, () -> { if (session != null && player.isOnline()) rooms.admit(player, options.rooms()); });
    }
}
