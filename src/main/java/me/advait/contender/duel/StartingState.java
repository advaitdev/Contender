package me.advait.contender.duel;

public final class StartingState extends AbstractDuelState {
    public StartingState(Duel duel) { super(duel); }

    @Override
    public boolean canAddSpectator() { return false; }

    @Override
    protected void onEnable() {
        if (duel.prepareStart()) duel.setState(new SortingState(duel));
        else duel.forceEnd();
    }
}
