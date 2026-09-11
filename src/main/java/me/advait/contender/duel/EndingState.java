package me.advait.contender.duel;

/** Keeps participants protected and the arena reserved until rollback completes. */
public final class EndingState extends AbstractDuelState {
    private final boolean forced;

    public EndingState(Duel duel, boolean forced) {
        super(duel);
        this.forced = forced;
    }

    boolean isForced() { return forced; }

    @Override
    public boolean isEnding() { return true; }

    @Override
    public boolean canAddSpectator() { return false; }

    @Override
    protected void onEnable() {
        if (forced) {
            cleanUp();
        } else {
            duel.announceResult();
            runLater(this::cleanUp, 60L);
        }
    }

    private void cleanUp() {
        duel.returnParticipantsToLobby();
        duel.rollbackArena(guard(duel::finish));
    }
}
