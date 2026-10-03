package me.advait.contender.tournament;

import java.util.*;
import java.util.function.Predicate;

public final class Tournament {
    public record Standing(TournamentEntry entry, int played, int wins, int draws, int losses, int points, int roundDifference) { }
    private final UUID id;
    private final String name;
    private final String mapId;
    private final String kitId;
    private final List<TournamentEntry> entries;
    private final List<TournamentMatch> matches;
    private final boolean teams;
    private final boolean waitForRound;
    private final int sortingSeconds;
    private final int maxParallel;
    private final int rounds;
    private final int schedule;
    private boolean running;
    private boolean cancelled;
    private boolean started;

    public Tournament(UUID id, String name, String mapId, String kitId, List<TournamentEntry> entries,
                      boolean teams, boolean waitForRound, int bestOf, int sortingSeconds, int maxParallel) {
        this(id, name, mapId, kitId, entries, teams, waitForRound, bestOf, sortingSeconds, maxParallel,
                RoundRobinSchedule.fullRounds(entries.size()));
    }
    public Tournament(UUID id, String name, String mapId, String kitId, List<TournamentEntry> entries,
                      boolean teams, boolean waitForRound, int bestOf, int sortingSeconds, int maxParallel, int rounds) {
        this(id, name, mapId, kitId, entries, teams, waitForRound, bestOf, sortingSeconds, maxParallel, rounds, RoundRobinSchedule.CURRENT);
    }
    public Tournament(UUID id, String name, String mapId, String kitId, List<TournamentEntry> entries,
                      boolean teams, boolean waitForRound, int bestOf, int sortingSeconds, int maxParallel, int rounds, int schedule) {
        if (name == null || name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Choose a tournament name between 1 and 64 characters.");
        if (sortingSeconds < 5 || sortingSeconds > 60 || maxParallel < 1 || maxParallel > 100) throw new IllegalArgumentException("Invalid tournament settings.");
        Set<UUID> players = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (TournamentEntry entry : entries) {
            if (!names.add(entry.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Each entry needs a different name.");
            if (!teams && entry.players().size() != 1) throw new IllegalArgumentException("Solo entries contain one player each.");
            for (UUID player : entry.players()) if (!players.add(player)) throw new IllegalArgumentException("A player can only enter once.");
        }
        this.id = id;
        this.name = name.strip();
        this.mapId = Objects.requireNonNull(mapId);
        this.kitId = Objects.requireNonNull(kitId);
        this.entries = List.copyOf(entries);
        this.teams = teams;
        this.waitForRound = waitForRound;
        this.sortingSeconds = sortingSeconds;
        this.maxParallel = maxParallel;
        this.rounds = rounds;
        this.schedule = schedule;
        List<TournamentMatch> scheduled = new ArrayList<>();
        for (var pairing : RoundRobinSchedule.create(entries.size(), rounds, schedule)) scheduled.add(new TournamentMatch(scheduled.size() + 1, pairing, bestOf));
        matches = List.copyOf(scheduled);
    }
    public UUID id() { return id; }
    public String name() { return name; }
    public String mapId() { return mapId; }
    /** Which {@link RoundRobinSchedule} layout the matches use. */
    public int schedule() { return schedule; }
    public String kitId() { return kitId; }
    public List<TournamentEntry> entries() { return entries; }
    public List<TournamentMatch> matches() { return matches; }
    public boolean teams() { return teams; }
    public boolean waitForRound() { return waitForRound; }
    public int sortingSeconds() { return sortingSeconds; }
    public int maxParallel() { return maxParallel; }
    public int rounds() { return rounds; }
    public boolean isRunning() { return running && !isComplete() && !cancelled; }
    public boolean isCancelled() { return cancelled; }
    public boolean isComplete() { return matches.stream().allMatch(m -> m.status() == TournamentMatch.Status.FINISHED); }
    public boolean isReserved(UUID player) {
        return !isComplete() && !cancelled && entries.stream().anyMatch(e -> e.players().contains(player));
    }
    public void resume() {
        if (isComplete() || cancelled) throw new IllegalStateException("This tournament has ended.");
        running = true;
        started = true;
    }
    /** Play has begun at least once (survives restarts). */
    public boolean started() { return started || matches.stream().anyMatch(m -> m.status() != TournamentMatch.Status.WAITING); }
    void restoreStarted(boolean value) { started = value; }
    public void pause() { running = false; }
    public void cancel() { cancelled = true; running = false; }
    public String statusText() { return cancelled ? "Cancelled" : isComplete() ? "Finished" : running ? "Playing" : "Paused"; }

    /** One bracket round's state: Finished, Playing or Not started (or the event's, when it's paused or cancelled). */
    public String roundStatus(int round) {
        List<TournamentMatch> inRound = matches.stream().filter(m -> m.round() == round).toList();
        if (cancelled) return "Cancelled";
        if (inRound.stream().allMatch(m -> m.status() == TournamentMatch.Status.FINISHED)) return "Finished";
        if (!running) return statusText();
        return inRound.stream().anyMatch(m -> m.status() == TournamentMatch.Status.PLAYING) ? "Playing" : "Not started";
    }

    /** Every match in this bracket round has a result. */
    public boolean roundFinished(int round) {
        return matches.stream().filter(m -> m.round() == round).allMatch(m -> m.status() == TournamentMatch.Status.FINISHED);
    }

    /** The caller starts each returned match before asking for the next candidate. */
    public TournamentMatch nextMatch(Predicate<UUID> available) {
        if (!isRunning()) return null;
        if (matches.stream().filter(m -> m.status() == TournamentMatch.Status.PLAYING).count() >= maxParallel) return null;
        int firstRound = matches.stream().filter(m -> m.status() != TournamentMatch.Status.FINISHED)
                .mapToInt(TournamentMatch::round).min().orElse(Integer.MAX_VALUE);
        Set<Integer> playing = new HashSet<>();
        for (TournamentMatch match : matches) {
            if (match.status() == TournamentMatch.Status.PLAYING) { playing.add(match.first()); playing.add(match.second()); }
        }
        for (TournamentMatch match : matches) {
            if (match.status() != TournamentMatch.Status.WAITING || waitForRound && match.round() != firstRound) continue;
            if (playing.contains(match.first()) || playing.contains(match.second())) continue;
            if (entries.get(match.first()).players().stream().allMatch(available)
                    && entries.get(match.second()).players().stream().allMatch(available)) return match;
        }
        return null;
    }

    public List<Standing> standings() {
        List<Standing> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            int played = 0, wins = 0, draws = 0, losses = 0, difference = 0;
            for (TournamentMatch match : matches) {
                if (match.status() != TournamentMatch.Status.FINISHED || match.first() != i && match.second() != i) continue;
                played++;
                boolean first = match.first() == i;
                var score = match.result();
                difference += first ? score.team1Score() - score.team2Score() : score.team2Score() - score.team1Score();
                if (score.winner() == null) draws++;
                else if (score.winner() == (first ? 1 : 2)) wins++;
                else losses++;
            }
            // One point per match won. Round difference only breaks ties in the order shown.
            result.add(new Standing(entries.get(i), played, wins, draws, losses, wins, difference));
        }
        result.sort(Comparator.comparingInt(Standing::points).reversed()
                .thenComparing(Comparator.comparingInt(Standing::roundDifference).reversed())
                .thenComparing(s -> s.entry().name(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }
}
