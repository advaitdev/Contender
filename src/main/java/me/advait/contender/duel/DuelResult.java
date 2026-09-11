package me.advait.contender.duel;

public record DuelResult(Reason reason, int team1Score, int team2Score, Integer winner) {
    public DuelResult {
        java.util.Objects.requireNonNull(reason);
        if (team1Score < 0 || team2Score < 0 || winner != null && winner != 1 && winner != 2
                || reason == Reason.FORFEIT && winner == null) throw new IllegalArgumentException("Invalid match result");
    }
    public enum Reason { FINISHED, FORFEIT, CANCELLED }
}
