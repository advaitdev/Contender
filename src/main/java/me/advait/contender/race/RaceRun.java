package me.advait.contender.race;

import java.util.*;

/** Race results are independent of Bukkit and of the arena's lifetime. Times use a monotonic clock. */
public final class RaceRun {
    public enum State { READY, COUNTDOWN, RUNNING, FINISHED, CANCELLED, INTERRUPTED }
    public enum Hit { IGNORED, TOO_FAR, CHECKPOINT, FINISH }
    public static final class Racer {
        private final UUID id;
        private final String name;
        private int checkpoint;
        private long finishNanos = -1;
        private int place;
        private boolean withdrawn;
        public Racer(UUID id, String name) { this.id = Objects.requireNonNull(id); this.name = Objects.requireNonNull(name); }
        public UUID id() { return id; }
        public String name() { return name; }
        public int checkpoint() { return checkpoint; }
        public long finishNanos() { return finishNanos; }
        public int place() { return place; }
        public boolean withdrawn() { return withdrawn; }
        public boolean done() { return finishNanos >= 0 || withdrawn; }
        void restore(int checkpoint, long finishNanos, int place, boolean withdrawn) {
            this.checkpoint = checkpoint; this.finishNanos = finishNanos; this.place = place; this.withdrawn = withdrawn;
        }
    }
    private final UUID id;
    private final String name, mapId, kitId;
    private final int maxAdvance, timeLimitSeconds;
    private final LinkedHashMap<UUID, Racer> racers = new LinkedHashMap<>();
    private State state = State.READY;
    private int checkpointCount, finishCount;
    private long startedNanos;
    public RaceRun(UUID id, String name, String mapId, int maxAdvance, int timeLimitSeconds, Collection<Racer> roster) {
        this(id, name, mapId, maxAdvance, timeLimitSeconds, roster, "");
    }
    public RaceRun(UUID id, String name, String mapId, int maxAdvance, int timeLimitSeconds, Collection<Racer> roster, String kitId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the race a name.");
        if (maxAdvance < 1 || maxAdvance > 1000) throw new IllegalArgumentException("Checkpoint jump must be between 1 and 1,000.");
        if (timeLimitSeconds < 0 || timeLimitSeconds > 86400) throw new IllegalArgumentException("Time limit must be between 0 and 1,440 minutes.");
        if (roster.isEmpty() || roster.size() > 64) throw new IllegalArgumentException("Enter between 1 and 64 racers.");
        for (Racer racer : roster) if (racers.putIfAbsent(racer.id(), racer) != null) throw new IllegalArgumentException("Each racer can only be entered once.");
        this.id = id; this.name = name; this.mapId = mapId; this.maxAdvance = maxAdvance; this.timeLimitSeconds = timeLimitSeconds;
        this.kitId = kitId == null ? "" : kitId.strip();
    }
    public UUID id() { return id; }
    public String name() { return name; }
    public String mapId() { return mapId; }
    public String kitId() { return kitId; }
    public int maxAdvance() { return maxAdvance; }
    public int timeLimitSeconds() { return timeLimitSeconds; }
    public State state() { return state; }
    public int checkpointCount() { return checkpointCount; }
    public List<Racer> racers() { return List.copyOf(racers.values()); }
    public Racer racer(UUID id) { return racers.get(id); }
    public boolean terminal() { return state == State.FINISHED || state == State.CANCELLED || state == State.INTERRUPTED; }
    public boolean active() { return state == State.COUNTDOWN || state == State.RUNNING; }
    public boolean allDone() { return racers.values().stream().allMatch(Racer::done); }
    public long elapsed(long now) { return state == State.RUNNING ? Math.max(0, now - startedNanos) : 0; }
    public void countdown(int checkpoints) {
        if (state != State.READY || checkpoints < 1) throw new IllegalStateException("The race is not ready to start.");
        checkpointCount = checkpoints; state = State.COUNTDOWN;
    }
    public void start(long now) {
        if (state != State.COUNTDOWN) throw new IllegalStateException("The countdown has not started.");
        startedNanos = now; state = State.RUNNING;
    }
    public Hit hit(UUID id, int target, long now) {
        Racer racer = racers.get(id);
        if (state != State.RUNNING || racer == null || racer.done() || target <= racer.checkpoint || target > checkpointCount + 1) return Hit.IGNORED;
        if (target - racer.checkpoint > maxAdvance) return Hit.TOO_FAR;
        racer.checkpoint = target;
        if (target == checkpointCount + 1) {
            racer.finishNanos = Math.max(0, now - startedNanos); racer.place = ++finishCount;
            return Hit.FINISH;
        }
        return Hit.CHECKPOINT;
    }
    public void withdraw(UUID id) { Racer racer = racers.get(id); if (racer != null && !racer.done()) racer.withdrawn = true; }
    public void end(State end) {
        if (end != State.FINISHED && end != State.CANCELLED && end != State.INTERRUPTED) throw new IllegalArgumentException("Invalid end state");
        state = end; racers.values().forEach(r -> { if (!r.done()) r.withdrawn = true; });
    }
    public List<Racer> standings() {
        return racers.values().stream().sorted(Comparator.comparingInt((Racer r) -> r.finishNanos >= 0 ? 0 : r.withdrawn ? 2 : 1)
                .thenComparingLong(r -> r.finishNanos >= 0 ? r.finishNanos : 0)
                .thenComparingInt(r -> r.finishNanos >= 0 ? r.place : -r.checkpoint)
                .thenComparing(Racer::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    void restore(State state, int checkpoints) {
        this.state = state; checkpointCount = checkpoints;
        finishCount = racers.values().stream().mapToInt(Racer::place).max().orElse(0);
        if (active()) end(State.INTERRUPTED);
    }
    public String statusText() { return switch (state) {
        case READY -> "Ready"; case COUNTDOWN -> "Starting"; case RUNNING -> "Racing";
        case FINISHED -> "Finished"; case CANCELLED -> "Cancelled"; case INTERRUPTED -> "Interrupted";
    }; }
    public static String time(long nanos) {
        long millis = Math.max(0, nanos / 1_000_000);
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }
}
