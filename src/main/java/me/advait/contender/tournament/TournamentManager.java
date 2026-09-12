package me.advait.contender.tournament;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelResult;
import me.advait.contender.duel.DuelSetup;
import me.advait.contender.game.AbstractGameState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/** The tournament's scheduler and board share the plugin's lifecycle. */
public final class TournamentManager extends AbstractGameState {
    private final Contender contender;
    private final TournamentStore store;
    private final TournamentBoard board;
    private final Map<Integer, Duel> playing = new HashMap<>();
    private Tournament tournament;
    private String waitingReason = "";

    public TournamentManager(Contender plugin) {
        super(plugin);
        contender = plugin;
        store = new TournamentStore(new File(plugin.getDataFolder(), "tournament.yml"));
        board = new TournamentBoard(plugin);
    }
    @Override protected void onEnable() {
        try { tournament = store.load(); }
        catch (Exception failure) { contender.getLogger().log(Level.SEVERE, "Could not load tournament.yml", failure); }
        board.enable();
        board.update(tournament, playing);
        runRepeating(this::tick, 20L, 20L);
    }
    @Override protected void onDisable() {
        if (tournament != null) { tournament.pause(); save(); }
        board.disable();
    }
    public Tournament current() { return tournament; }
    public TournamentBoard board() { return board; }
    public Map<Integer, Duel> playing() { return Collections.unmodifiableMap(playing); }
    public String waitingReason() { return waitingReason; }
    public boolean isReserved(UUID uuid) { return tournament != null && tournament.isReserved(uuid); }
    public void create(Tournament tournament) {
        if (this.tournament != null && !this.tournament.isComplete() && !this.tournament.isCancelled()) {
            throw new IllegalStateException("Finish or cancel the current tournament first.");
        }
        if (!playing.isEmpty()) throw new IllegalStateException("Wait for the last matches to finish cleaning up.");
        if (contender.getMapManager().getMap(tournament.mapId()) == null || contender.getKitManager().getKit(tournament.kitId()) == null) {
            throw new IllegalArgumentException("Choose a saved map and kit.");
        }
        requireEligibleEntries(tournament);
        if (contender.getMinigameManager() != null) contender.getMinigameManager().selectRoundRobin();
        else if (contender.getRaceManager() != null) contender.getRaceManager().selectRoundRobin();
        this.tournament = tournament;
        waitingReason = "";
        save();
        board.update(tournament, playing);
    }
    public void resume() {
        if (contender.getMinigameManager() != null && contender.getMinigameManager().active()) throw new IllegalStateException("Finish the minigame first.");
        if (contender.getRaceManager() != null && contender.getRaceManager().active()) throw new IllegalStateException("Finish the race first.");
        if (tournament == null) throw new IllegalStateException("Create a tournament first.");
        requireEligibleEntries(tournament);
        if (contender.getVoteManager().isVoteActive()) throw new IllegalStateException("Wait for the vote to finish.");
        if (contender.getArenaManager().available(tournament.mapId()) == 0 && playing.isEmpty()) {
            throw new IllegalStateException("Prepare this map's arenas with /arena before starting.");
        }
        tournament.resume();
        save();
        tick();
    }
    public void pause() {
        if (tournament != null) { tournament.pause(); save(); board.update(tournament, playing); }
    }
    public void cancel() {
        if (tournament == null) return;
        tournament.cancel();
        save();
        if (contender.getTabManager() != null) contender.getTabManager().refresh();
        for (Duel duel : new ArrayList<>(playing.values())) duel.forceEnd();
        board.update(tournament, playing);
        if (contender.getTabManager() != null) contender.getTabManager().refresh();
    }
    /** Clear scheduler state before the coordinator forcibly tears down every duel. */
    public void forceCancel() {
        if (tournament != null) tournament.cancel();
        playing.clear();
        waitingReason = "";
        save();
        board.update(tournament, playing);
        if (contender.getTabManager() != null) contender.getTabManager().refresh();
    }
    /** Pause before cancelling so the scheduler cannot immediately restart this pairing. */
    public boolean cancelDuel(Duel duel) {
        boolean tournamentMatch = playing.containsValue(duel);
        if (tournamentMatch) pause();
        duel.forceEnd();
        board.update(tournament, playing);
        if (contender.getTabManager() != null) contender.getTabManager().refresh();
        return tournamentMatch;
    }
    public void setBestOf(TournamentMatch match, int rounds) {
        requireMatch(match);
        match.setBestOf(rounds);
        save();
    }
    public void award(TournamentMatch match, int side) {
        requireMatch(match);
        if (match.status() != TournamentMatch.Status.WAITING) throw new IllegalStateException("Only waiting matches can be awarded.");
        if (side != 1 && side != 2) throw new IllegalArgumentException("Choose one of the two entries.");
        match.finish(new DuelResult(DuelResult.Reason.FORFEIT, 0, 0, side));
        save();
        board.update(tournament, playing);
    }
    private void requireMatch(TournamentMatch match) {
        if (tournament == null || tournament.isCancelled() || !tournament.matches().contains(match)) throw new IllegalStateException("That tournament is no longer active.");
    }
    public void save() { if (tournament != null) store.save(tournament); }

    private void tick() {
        board.update(tournament, playing);
        if (tournament == null) return;
        if (contender.getMinigameManager() != null && contender.getMinigameManager().active()) return;
        if (!tournament.isRunning() || contender.getVoteManager().isVoteActive() || contender.getRaceManager() != null && contender.getRaceManager().active()) return;
        waitingReason = "Waiting for players or a free arena.";
        while (contender.getArenaManager().available(tournament.mapId()) > 0) {
            TournamentMatch match = tournament.nextMatch(this::available);
            if (match == null) break;
            DuelSetup setup = new DuelSetup(tournament.id());
            setup.setSelectedMap(contender.getMapManager().getMap(tournament.mapId()));
            setup.setSelectedKit(contender.getKitManager().getKit(tournament.kitId()));
            setup.setRounds(match.bestOf());
            setup.setPreRoundDelay(tournament.sortingSeconds());
            tournament.entries().get(match.first()).players().forEach(setup.getTeam1()::addPlayer);
            tournament.entries().get(match.second()).players().forEach(setup.getTeam2()::addPlayer);
            // Team labels survive into match messages and results.
            setup.getTeam1().setName(tournament.entries().get(match.first()).name());
            setup.getTeam2().setName(tournament.entries().get(match.second()).name());
            Tournament startedTournament = tournament;
            match.start();
            try {
                Duel duel = contender.getDuelManager().startDuel(setup, result -> {
                    if (tournament != startedTournament) return;
                    playing.remove(match.number());
                    match.finish(result);
                    save();
                    board.update(tournament, playing);
                });
                if (!duel.isFinished()) playing.put(match.number(), duel);
                save();
            } catch (Exception | LinkageError failure) {
                match.retry();
                waitingReason = failure.getMessage() == null ? "Could not start the next match." : failure.getMessage();
                tournament.pause();
                save();
                contender.getLogger().log(Level.WARNING, "Tournament paused: " + waitingReason, failure);
                break;
            }
        }
    }
    private boolean available(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && !player.isDead() && !contender.getDuelManager().isPlaying(uuid)
                && contender.getDuelManager().isEligible(uuid);
    }
    private void requireEligibleEntries(Tournament tournament) {
        for (TournamentEntry entry : tournament.entries()) {
            for (UUID uuid : entry.players()) contender.getDuelManager().requireEligible(uuid);
        }
    }
}
