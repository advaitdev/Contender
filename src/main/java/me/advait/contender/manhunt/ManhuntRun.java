package me.advait.contender.manhunt;

import java.util.*;

/** The result and team roster are independent of Bukkit and live world ownership. */
public final class ManhuntRun {
    public enum Team { RUNNER, HUNTER }
    public enum State { READY, COUNTDOWN, RUNNING, RUNNERS_WON, HUNTERS_WON, CANCELLED, INTERRUPTED }
    public record Entry(UUID id, String name, Team team) {
        public Entry { Objects.requireNonNull(id); Objects.requireNonNull(team); if (name == null || name.isBlank()) throw new IllegalArgumentException("Give every player a name."); }
    }
    private final UUID id;
    private final String name, kitId, worldName;
    private final List<Entry> entries;
    private final int countdownSeconds;
    private final Set<UUID> out = new LinkedHashSet<>();
    private State state = State.READY;

    public ManhuntRun(UUID id, String name, String kitId, String worldName, List<Entry> entries, int countdownSeconds) {
        this.id = Objects.requireNonNull(id);
        if (name == null || name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Choose a tournament name between 1 and 64 characters.");
        this.name = name; this.kitId = Objects.requireNonNull(kitId); this.worldName = Objects.requireNonNull(worldName);
        if (entries.size() < 2 || entries.size() > 64) throw new IllegalArgumentException("Enter between 2 and 64 contestants.");
        Set<UUID> ids = new HashSet<>();
        for (Entry entry : entries) if (!ids.add(entry.id())) throw new IllegalArgumentException(entry.name() + " is listed more than once.");
        if (entries.stream().noneMatch(e -> e.team() == Team.RUNNER) || entries.stream().noneMatch(e -> e.team() == Team.HUNTER))
            throw new IllegalArgumentException("Each team needs at least one player.");
        if (countdownSeconds < 3 || countdownSeconds > 120) throw new IllegalArgumentException("Choose a countdown between 3 and 120 seconds.");
        this.entries = List.copyOf(entries); this.countdownSeconds = countdownSeconds;
    }
    public static List<Entry> randomTeams(List<Entry> players, Random random) {
        List<Entry> shuffled = new ArrayList<>(players); Collections.shuffle(shuffled, random);
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < shuffled.size(); i++) {
            Entry player = shuffled.get(i);
            result.add(new Entry(player.id(), player.name(), i < shuffled.size() / 2 ? Team.RUNNER : Team.HUNTER));
        }
        return List.copyOf(result);
    }
    public UUID id() { return id; }
    public String name() { return name; }
    public String kitId() { return kitId; }
    public String worldName() { return worldName; }
    public int countdownSeconds() { return countdownSeconds; }
    public List<Entry> entries() { return entries; }
    public State state() { return state; }
    public Set<UUID> out() { return Set.copyOf(out); }
    public Entry entry(UUID id) { return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null); }
    public boolean alive(UUID id) { return entry(id) != null && !out.contains(id); }
    public long alive(Team team) { return entries.stream().filter(e -> e.team() == team && alive(e.id())).count(); }
    public boolean terminal() { return state.ordinal() >= State.RUNNERS_WON.ordinal(); }
    public void countdown() { if (state != State.READY) throw new IllegalStateException("This game cannot be started."); state = State.COUNTDOWN; }
    public void start() { if (state != State.COUNTDOWN) throw new IllegalStateException("The countdown has not started."); state = State.RUNNING; }
    public boolean eliminate(UUID id) {
        if (terminal() || entry(id) == null || !out.add(id)) return false;
        if (state == State.RUNNING && alive(Team.RUNNER) == 0) state = State.HUNTERS_WON;
        return true;
    }
    public boolean dragonDied() {
        if (state != State.RUNNING) return false;
        state = State.RUNNERS_WON; return true;
    }
    public void end(State result) {
        if (!terminal()) {
            if (result.ordinal() < State.RUNNERS_WON.ordinal()) throw new IllegalArgumentException("Choose an end result.");
            state = result;
        }
    }
    void restore(State saved, Collection<UUID> eliminated) {
        if (!entries.stream().map(Entry::id).toList().containsAll(eliminated)) throw new IllegalArgumentException("Unknown player in Manhunt results.");
        state = saved; out.addAll(eliminated);
        if (state == State.COUNTDOWN || state == State.RUNNING) state = State.INTERRUPTED;
    }
    public String statusText() {
        return switch (state) {
            case READY -> "Ready"; case COUNTDOWN -> "Starting"; case RUNNING -> "Playing";
            case RUNNERS_WON -> "Runners Win"; case HUNTERS_WON -> "Hunters Win";
            case CANCELLED -> "Cancelled"; case INTERRUPTED -> "Interrupted";
        };
    }
}
