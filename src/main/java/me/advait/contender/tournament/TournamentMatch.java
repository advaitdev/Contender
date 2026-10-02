package me.advait.contender.tournament;

import me.advait.contender.duel.DuelResult;

public final class TournamentMatch {
    public enum Status { WAITING, PLAYING, FINISHED }
    private final int number;
    private final RoundRobinSchedule.Pairing pairing;
    private int bestOf;
    private Status status = Status.WAITING;
    private DuelResult result;

    public TournamentMatch(int number, RoundRobinSchedule.Pairing pairing, int bestOf) {
        this.number = number;
        this.pairing = pairing;
        setBestOf(bestOf);
    }
    public int number() { return number; }
    public int round() { return pairing.round(); }
    public int first() { return pairing.first(); }
    public int second() { return pairing.second(); }
    public int bestOf() { return bestOf; }
    public Status status() { return status; }
    public DuelResult result() { return result; }
    public void setBestOf(int bestOf) {
        if (bestOf < 1 || bestOf > 15 || bestOf % 2 == 0) throw new IllegalArgumentException("Choose an odd number from 1 to 15.");
        if (status != Status.WAITING) throw new IllegalStateException("This match has already started.");
        this.bestOf = bestOf;
    }
    public void start() {
        if (status != Status.WAITING) throw new IllegalStateException("Match has already started.");
        status = Status.PLAYING;
    }
    public void retry() { if (status == Status.PLAYING) status = Status.WAITING; }
    /** Replaces a finished match's result: a director's correction. */
    public void correct(DuelResult result) {
        if (status != Status.FINISHED) throw new IllegalStateException("Only finished matches can be changed.");
        if (result.reason() == DuelResult.Reason.CANCELLED) throw new IllegalArgumentException("Choose a result.");
        this.result = result;
    }
    /** Clears a finished match so it is played again. */
    public void reopen() {
        if (status != Status.FINISHED) throw new IllegalStateException("Only finished matches can be replayed.");
        result = null;
        status = Status.WAITING;
    }
    public void finish(DuelResult result) {
        if (status == Status.FINISHED) return;
        if (result.reason() == DuelResult.Reason.CANCELLED) { retry(); return; }
        this.result = result;
        status = Status.FINISHED;
    }
}
