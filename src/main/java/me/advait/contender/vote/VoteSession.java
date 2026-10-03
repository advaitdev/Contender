package me.advait.contender.vote;

import java.util.*;

/** Candidates, their numbers, and who voted for whom. Pure data, so it can be tested without a server. */
public final class VoteSession {
    public record Candidate(UUID id, String name, int number) { }
    public record Tally(Candidate candidate, int votes) { }

    private final Map<UUID, Candidate> candidates = new LinkedHashMap<>();
    private final Map<Integer, Candidate> byNumber = new HashMap<>();
    private final Set<UUID> removed = new HashSet<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    /** Who can vote, by name: the contestants who aren't sitting out, plus anyone who joins and votes. */
    private final Map<UUID, String> voters = new LinkedHashMap<>();
    private final Set<UUID> sittingOut;
    private final boolean liveCounts;
    private final long endsAt;
    private boolean closed;

    public VoteSession(List<Candidate> roster, boolean liveCounts, long endsAt) {
        this(roster, roster.stream().collect(LinkedHashMap::new, (map, c) -> map.put(c.id(), c.name()), Map::putAll), Set.of(), liveCounts, endsAt);
    }

    /**
     * @param voters     everyone who can vote; safe players vote without being on the ballot
     * @param sittingOut players who can't vote (and aren't on the ballot)
     */
    public VoteSession(List<Candidate> roster, Map<UUID, String> voters, Set<UUID> sittingOut, boolean liveCounts, long endsAt) {
        for (Candidate candidate : roster) {
            candidates.put(candidate.id(), candidate);
            byNumber.put(candidate.number(), candidate);
        }
        this.voters.putAll(voters);
        this.sittingOut = Set.copyOf(sittingOut);
        this.voters.keySet().removeAll(this.sittingOut);
        this.liveCounts = liveCounts;
        this.endsAt = endsAt;
    }

    /** Numbers candidates 1, 2, 3… in the order given. */
    public static List<Candidate> number(List<Map.Entry<UUID, String>> players) {
        List<Candidate> numbered = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) numbered.add(new Candidate(players.get(i).getKey(), players.get(i).getValue(), i + 1));
        return numbered;
    }

    public boolean liveCounts() { return liveCounts; }
    public long endsAt() { return endsAt; }
    public boolean closed() { return closed; }
    void close() { closed = true; }

    public List<Candidate> candidates() {
        return candidates.values().stream().filter(c -> !removed.contains(c.id())).toList();
    }

    /** Everyone who can vote, by name, in the order they were added. */
    public Map<UUID, String> voters() { return Collections.unmodifiableMap(voters); }
    public boolean sittingOut(UUID player) { return sittingOut.contains(player); }
    /** A contestant who joined after the vote started. */
    public void addVoter(UUID player, String name) { if (!sittingOut.contains(player)) voters.putIfAbsent(player, name); }

    public Candidate candidate(UUID id) { return removed.contains(id) ? null : candidates.get(id); }
    public Candidate byNumber(int number) {
        Candidate candidate = byNumber.get(number);
        return candidate == null || removed.contains(candidate.id()) ? null : candidate;
    }

    public void vote(UUID voter, Candidate target) {
        if (closed) throw new IllegalStateException("Voting has closed.");
        if (sittingOut.contains(voter)) throw new IllegalArgumentException("You're sitting out this vote.");
        if (target == null || removed.contains(target.id())) throw new IllegalArgumentException("That player is no longer a candidate.");
        if (target.id().equals(voter)) throw new IllegalArgumentException("You can't vote for yourself.");
        votes.put(voter, target.id());
    }

    public void unvote(UUID voter) { if (!closed) votes.remove(voter); }

    public UUID voteOf(UUID voter) { return votes.get(voter); }

    public void remove(UUID candidate) {
        removed.add(candidate);
        votes.values().removeIf(candidate::equals);
    }

    public int votesFor(UUID candidate) {
        int count = 0;
        for (UUID target : votes.values()) if (target.equals(candidate)) count++;
        return count;
    }

    public int totalVotes() { return votes.size(); }

    /** Every candidate with their votes, most votes first; ties keep candidate order. */
    public List<Tally> results() {
        List<Tally> tallies = new ArrayList<>();
        for (Candidate candidate : candidates()) tallies.add(new Tally(candidate, votesFor(candidate.id())));
        tallies.sort(Comparator.comparingInt(Tally::votes).reversed().thenComparingInt(t -> t.candidate().number()));
        return tallies;
    }

    /** Candidates sharing the most votes. Empty when nobody received a vote. */
    public List<Candidate> leaders() {
        List<Tally> results = results();
        if (results.isEmpty() || results.getFirst().votes() == 0) return List.of();
        int top = results.getFirst().votes();
        return results.stream().filter(t -> t.votes() == top).map(Tally::candidate).toList();
    }
}
