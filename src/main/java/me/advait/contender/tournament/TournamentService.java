package me.advait.contender.tournament;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelResult;
import me.advait.contender.duel.DuelSettings;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.stage.Stage;
import me.advait.contender.tab.BracketLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;

/** Schedules round-robin matches as players and arena copies become free. */
public final class TournamentService extends Module {
    public static final String KIND = "round_robin";
    private final TournamentStore store;
    private final Map<Integer, Duel> playing = new HashMap<>();
    private final RoundRobinStage stage = new RoundRobinStage();
    private Tournament tournament;
    private String waitingReason = "";

    public TournamentService(Contender plugin) {
        super(plugin);
        store = new TournamentStore(new File(plugin.getDataFolder(), "tournament.yml"));
    }

    @Override protected void onEnable() {
        try { tournament = store.load(); }
        catch (Exception failure) { plugin.getLogger().log(Level.SEVERE, "Could not load tournament.yml", failure); }
        String saved = plugin.getStages().savedKind();
        if (tournament != null && (saved.isEmpty() || saved.equals(KIND)) && plugin.getStages().current() == null) {
            plugin.getStages().restore(stage);
        }
        tasks.repeat(20, 20, this::tick);
    }

    @Override protected void onDisable() {
        if (tournament != null) { tournament.pause(); save(); }
        playing.clear();
    }

    public Tournament current() { return tournament; }
    public Stage stage() { return stage; }
    public Map<Integer, Duel> playing() { return Collections.unmodifiableMap(playing); }
    public String waitingReason() { return waitingReason; }
    public boolean isSelected() { return tournament != null && plugin.getStages().current() == stage; }

    public void create(Tournament created) {
        if (plugin.getStages().current() != stage || tournament == null || !tournament.isComplete() && !tournament.isCancelled()) {
            plugin.getStages().requireFree();
        }
        if (!playing.isEmpty()) throw new IllegalStateException("Wait for the last matches to finish.");
        ArenaMap map = plugin.getMapManager().getMap(created.mapId());
        if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with both spawns set.");
        if (plugin.getKitManager().getKit(created.kitId()) == null) throw new IllegalArgumentException("Choose a saved kit.");
        requireEligible(created);
        tournament = created;
        waitingReason = "";
        save();
        plugin.getStages().select(stage, KIND);
    }

    public void resume() {
        Tournament current = require();
        if (plugin.getStages().current() != stage) throw new IllegalStateException("Another event is selected. Finish or cancel it first.");
        requireEligible(current);
        boolean first = !current.started();
        current.resume();
        waitingReason = "";
        save();
        if (first) announceStart(current);
        plugin.getStages().started(stage);
        tick();
    }

    public void pause() {
        waitingReason = "";
        if (tournament == null) return;
        tournament.pause();
        save();
        plugin.getStages().refreshDisplays();
    }

    /** Ends the tournament and its running matches; results so far are kept. */
    public void cancel() {
        if (tournament == null) return;
        tournament.cancel();
        waitingReason = "";
        save();
        for (Duel duel : List.copyOf(playing.values())) duel.forceStop("The tournament was cancelled.");
        playing.clear();
        plugin.getStages().ended(stage);
    }

    /** Cancels one match without recording a result; the pairing goes back into the queue. */
    public boolean cancelMatch(Duel duel) {
        boolean ours = playing.containsValue(duel);
        if (ours) {
            pause();
            waitingReason = "A match was cancelled. Resume when you're ready to replay it.";
        }
        duel.forceStop("This match was cancelled by a director.");
        return ours;
    }

    public void setBestOf(TournamentMatch match, int bestOf) {
        requireMatch(match);
        match.setBestOf(bestOf);
        save();
    }

    public void award(TournamentMatch match, int side) {
        requireMatch(match);
        if (match.status() != TournamentMatch.Status.WAITING) throw new IllegalStateException("Only waiting matches can be awarded.");
        if (side != 1 && side != 2) throw new IllegalArgumentException("Choose one of the two entries.");
        match.finish(new DuelResult(DuelResult.Reason.FORFEIT, 0, 0, side));
        save();
        afterResult();
    }

    public void save() {
        if (tournament == null) return;
        try { store.save(tournament); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.SEVERE, "Could not save tournament.yml", failure); }
    }

    private Tournament require() {
        if (tournament == null) throw new IllegalStateException("Create a tournament first.");
        return tournament;
    }

    private void requireMatch(TournamentMatch match) {
        if (tournament == null || tournament.isCancelled() || !tournament.matches().contains(match)) {
            throw new IllegalStateException("That tournament is no longer active.");
        }
    }

    private void requireEligible(Tournament target) {
        for (TournamentEntry entry : target.entries()) for (UUID id : entry.players()) {
            if (plugin.getRoleManager().getRole(id) != PlayerRole.CONTESTANT) {
                String name = entry.playerNames().getOrDefault(id, entry.name());
                throw new IllegalStateException(name + " is not a contestant. Change their role or remove them from the roster.");
            }
        }
    }

    // ---- Scheduling ----------------------------------------------------------------------------

    private void tick() {
        if (tournament == null || !tournament.isRunning() || plugin.getStages().current() != stage) return;
        if (plugin.getVotes().isActive()) { waitingReason = "Paused for the vote."; return; }
        ArenaMap map = plugin.getMapManager().getMap(tournament.mapId());
        Kit kit = plugin.getKitManager().getKit(tournament.kitId());
        if (map == null || kit == null) {
            waitingReason = map == null ? "The tournament's map was deleted." : "The tournament's kit was deleted.";
            tournament.pause();
            save();
            return;
        }
        boolean started = false;
        while (tournament.isRunning() && plugin.getArenas().ready(map.getId()) > 0) {
            TournamentMatch match = tournament.nextMatch(this::available);
            if (match == null) break;
            if (!startMatch(match, map, kit)) break;
            started = true;
        }
        if (tournament.isRunning()) {
            boolean waitingMatches = tournament.matches().stream().anyMatch(m -> m.status() == TournamentMatch.Status.WAITING);
            if (!waitingMatches) waitingReason = "Finishing the last matches.";
            else if (plugin.getArenas().ready(map.getId()) == 0) waitingReason = "Waiting for an arena: " + plugin.getArenas().readiness(map.getId());
            else waitingReason = "Waiting for players to be free and online.";
        }
        if (started) plugin.getStages().refreshDisplays();
    }

    private boolean startMatch(TournamentMatch match, ArenaMap map, Kit kit) {
        TournamentEntry first = tournament.entries().get(match.first()), second = tournament.entries().get(match.second());
        Tournament owner = tournament;
        match.start();
        try {
            DuelSettings settings = new DuelSettings(map, kit, match.bestOf() / 2 + 1, tournament.sortingSeconds(), false,
                    Math.clamp(plugin.getConfig().getInt("matches.reconnect-seconds", 60), 0, 600));
            Duel duel = plugin.getDuels().start(settings, List.of(first.players(), second.players()),
                    List.of(first.name(), second.name()), "Match #" + match.number(), result -> recordResult(owner, match, result));
            playing.put(match.number(), duel);
            save();
            return true;
        } catch (RuntimeException failure) {
            match.retry();
            waitingReason = Msg.reason(failure);
            plugin.getLogger().warning("Could not start match #" + match.number() + ": " + waitingReason);
            return false;
        }
    }

    private void recordResult(Tournament owner, TournamentMatch match, DuelResult result) {
        if (tournament != owner) return;
        playing.remove(match.number());
        if (result.reason() == DuelResult.Reason.CANCELLED) {
            match.retry();
            save();
            plugin.getStages().refreshDisplays();
            return;
        }
        match.finish(result);
        save();
        afterResult();
    }

    private void afterResult() {
        plugin.getStages().refreshDisplays();
        if (tournament != null && tournament.isComplete()) announceWinners(tournament);
    }

    private boolean available(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && !player.isDead() && plugin.getRoleManager().getRole(id) == PlayerRole.CONTESTANT
                && !plugin.getRegistry().isPlaying(id);
    }

    private void announceStart(Tournament started) {
        var theme = plugin.getThemes().current();
        Msg.broadcast(Msg.text(started.name(), DialogPalette.ACCENT).append(Msg.text(" has started. ", DialogPalette.TEXT))
                .append(Msg.text(started.matches().size() + " matches.", DialogPalette.MUTED)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Msg.title(player, Component.text(started.name(), theme.primary()), Msg.text("Let the games begin", DialogPalette.MUTED), 10, 50, 15);
            Sounds.ANNOUNCE.play(player);
        }
    }

    private void announceWinners(Tournament finished) {
        var standings = finished.standings();
        if (standings.isEmpty()) return;
        int top = standings.getFirst().points();
        List<String> leaders = standings.stream().filter(s -> s.points() == top).map(s -> s.entry().name()).toList();
        var theme = plugin.getThemes().current();
        Msg.broadcast(Msg.text(finished.name() + " is complete.", DialogPalette.ACCENT));
        for (int i = 0; i < Math.min(5, standings.size()); i++) {
            var row = standings.get(i);
            Msg.broadcast(Msg.text("  " + (i + 1) + ". ", DialogPalette.MUTED).append(Msg.text(row.entry().name(), DialogPalette.TEXT))
                    .append(Msg.text("  " + row.points() + (row.points() == 1 ? " win" : " wins"), DialogPalette.ACCENT)));
        }
        Component subtitle = Msg.text(leaders.size() == 1 ? "wins with " + top + (top == 1 ? " point" : " points") : "tied at " + top + " points", DialogPalette.MUTED);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Msg.title(player, Component.text(String.join(" & ", leaders), theme.primary()), subtitle, 10, 70, 20);
            Sounds.VICTORY.play(player);
        }
        plugin.getStages().ended(stage);
    }

    /** The round robin as a stage, for the tab list, board, hack plans and sabotages. */
    private final class RoundRobinStage implements Stage {
        @Override public UUID id() { return tournament == null ? new UUID(0, 0) : tournament.id(); }
        @Override public String name() { return tournament == null ? "Round Robin" : tournament.name(); }
        @Override public String kind() { return "Round Robin"; }
        @Override public String statusText() { return tournament == null ? "Not created" : tournament.statusText(); }
        @Override public boolean started() { return tournament != null && tournament.started(); }
        @Override public boolean finished() { return tournament == null || tournament.isComplete() || tournament.isCancelled(); }
        @Override public boolean cancelled() { return tournament != null && tournament.isCancelled(); }
        @Override public boolean involves(UUID player) {
            return tournament != null && tournament.entries().stream().anyMatch(entry -> entry.players().contains(player));
        }
        @Override public boolean hasRounds() { return true; }
        @Override public int rounds() { return tournament == null ? 1 : BracketLayout.rounds(tournament); }
        @Override public BracketLayout.Layout layout(BracketLayout.View view, Function<UUID, BracketLayout.Presence> presence, List<UUID> others) {
            if (tournament == null) return null;
            Map<Integer, BracketLayout.Score> scores = new HashMap<>();
            playing.forEach((number, duel) -> scores.put(number, new BracketLayout.Score(duel.teams().get(0).score(), duel.teams().get(1).score())));
            LinkedHashSet<UUID> roster = new LinkedHashSet<>();
            tournament.entries().forEach(entry -> roster.addAll(entry.players()));
            roster.addAll(others);
            return BracketLayout.render(tournament, scores, presence, List.copyOf(roster), view);
        }
        @Override public String caption(BracketLayout.Layout layout) { return layout == null ? "Bracket" : layout.heading(); }
    }
}
