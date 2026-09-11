package me.advait.contender.duel;

/** Terminal state: retained for inspection, never registered as a listener. */
public final class EndedState extends AbstractDuelState {
    public EndedState(Duel duel) { super(duel); }

    @Override
    public boolean isFinished() { return true; }

    @Override
    public boolean canAddSpectator() { return false; }
}
