package me.advait.contender.combo;

import java.util.*;

/** The turn order and scoring rules have no dependency on Bukkit. */
public final class ComboRun {
    public enum State { READY, RUNNING, FINISHED, CANCELLED, INTERRUPTED }
    public enum Result { IGNORED, RETRY, SCORED }
    public static final class Entry {
        private final UUID id;
        private final String name;
        private Integer score;
        private boolean cleared, withdrawn;
        public Entry(UUID id, String name) { this.id = Objects.requireNonNull(id); this.name = Objects.requireNonNull(name); }
        public UUID id() { return id; }
        public String name() { return name; }
        public Integer score() { return score; }
        public boolean cleared() { return cleared; }
        public boolean withdrawn() { return withdrawn; }
        public boolean done() { return score != null || cleared || withdrawn; }
        public String value() { return cleared ? "∞" : score != null ? score.toString() : withdrawn ? "Did Not Finish" : "Waiting"; }
        void restore(Integer score, boolean cleared, boolean withdrawn) { this.score = score; this.cleared = cleared; this.withdrawn = withdrawn; }
    }
    private final UUID id;
    private final String name, mapId, kitId;
    private final ComboDifficulty difficulty;
    private final int graceHits;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    private State state = State.READY;
    private UUID playing;
    private int hits;
    public ComboRun(UUID id, String name, String mapId, String kitId, ComboDifficulty difficulty, int graceHits, Collection<Entry> roster) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the event a name.");
        if (graceHits < 0 || graceHits > 100) throw new IllegalArgumentException("Choose between 0 and 100 retry hits.");
        if (roster.isEmpty() || roster.size() > 64) throw new IllegalArgumentException("Enter between 1 and 64 players.");
        for (Entry entry : roster) if (entries.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("Each player can only be entered once.");
        this.id = Objects.requireNonNull(id); this.name = name; this.mapId = Objects.requireNonNull(mapId);
        this.kitId = Objects.requireNonNull(kitId); this.difficulty = Objects.requireNonNull(difficulty); this.graceHits = graceHits;
    }
    public UUID id() { return id; }
    public String name() { return name; }
    public String mapId() { return mapId; }
    public String kitId() { return kitId; }
    public ComboDifficulty difficulty() { return difficulty; }
    public int graceHits() { return graceHits; }
    public State state() { return state; }
    public UUID playing() { return playing; }
    public int hits() { return hits; }
    public List<Entry> entries() { return List.copyOf(entries.values()); }
    public Entry entry(UUID id) { return entries.get(id); }
    public boolean terminal() { return state == State.FINISHED || state == State.CANCELLED || state == State.INTERRUPTED; }
    public boolean allDone() { return entries.values().stream().allMatch(Entry::done); }
    public void start() { if (state != State.READY) throw new IllegalStateException("This event cannot be started."); state = State.RUNNING; }
    public void begin(UUID id) {
        if (state != State.RUNNING || playing != null || entry(id) == null || entry(id).done()) throw new IllegalStateException("That player cannot start a turn.");
        playing = id; hits = 0;
    }
    public boolean hit(UUID id) { if (state != State.RUNNING || !Objects.equals(playing, id)) return false; if (hits < Integer.MAX_VALUE) hits++; return true; }
    public void retry() { if (playing != null) hits = 0; }
    public Result hitBack() {
        if (playing == null || hits == 0) return Result.IGNORED;
        if (hits <= graceHits) { hits = 0; return Result.RETRY; }
        score(false); return Result.SCORED;
    }
    public boolean clear() { if (playing == null || hits == 0) return false; score(true); return true; }
    public void disconnect(UUID id) {
        if (!Objects.equals(playing, id)) return;
        if (hits > graceHits) score(false);
        else { playing = null; hits = 0; }
    }
    public void withdraw(UUID id) {
        Entry entry = entries.get(id); if (entry == null || entry.done()) return;
        if (Objects.equals(playing, id) && hits > graceHits) score(false);
        else { entry.withdrawn = true; if (Objects.equals(playing, id)) { playing = null; hits = 0; } }
    }
    private void score(boolean cleared) {
        Entry entry = entries.get(playing); entry.score = hits; entry.cleared = cleared; playing = null;
    }
    public void end(State end) {
        if (end == State.READY || end == State.RUNNING) throw new IllegalArgumentException("Invalid end state");
        state = end; playing = null;
        entries.values().stream().filter(e -> !e.done()).forEach(e -> e.withdrawn = true);
    }
    void restore(State state) { this.state = state; if (state == State.RUNNING) end(State.INTERRUPTED); }
    public List<Entry> standings() {
        return entries.values().stream().sorted(Comparator.comparingInt((Entry e) -> e.cleared ? 0 : e.score != null ? 1 : e.withdrawn ? 3 : 2)
                .thenComparing(Comparator.comparingInt((Entry e) -> e.cleared || e.score == null ? 0 : e.score).reversed())
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public String statusText() { return switch (state) {
        case READY -> "Ready"; case RUNNING -> playing == null ? "Waiting for Players" : "Playing";
        case FINISHED -> "Finished"; case CANCELLED -> "Cancelled"; case INTERRUPTED -> "Interrupted";
    }; }
}
