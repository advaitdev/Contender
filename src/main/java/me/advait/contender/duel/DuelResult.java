package me.advait.contender.duel;

/** The outcome of a two-sided match. Winner is 1, 2, or null for a draw. */
public record DuelResult(Reason reason, int team1Score, int team2Score, Integer winner) {
    public DuelResult {
        java.util.Objects.requireNonNull(reason);
        if (team1Score < 0 || team2Score < 0 || winner != null && winner != 1 && winner != 2
                || reason == Reason.FORFEIT && winner == null) throw new IllegalArgumentException("Invalid match result");
    }
    public enum Reason { FINISHED, FORFEIT, CANCELLED }
    public static DuelResult cancelled(int team1Score, int team2Score) { return new DuelResult(Reason.CANCELLED, team1Score, team2Score, null); }
}
