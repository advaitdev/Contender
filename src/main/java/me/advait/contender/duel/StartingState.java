package me.advait.contender.duel;

public final class StartingState extends AbstractDuelState {
    private final DuelResetRetry retry;
    private boolean holding;
    public StartingState(Duel duel) {
        super(duel);
        retry = new DuelResetRetry(duel, (action, delay) -> runLater(action, delay), "preparation");
    }

    @Override
    public boolean canAddSpectator() { return false; }

    @Override
    protected void onEnable() { prepare(); }

    private void prepare() {
        boolean prepared;
        try { prepared = duel.prepareStart(); }
        catch (RuntimeException failure) {
            if (!holding) { holding = true; runRepeating(duel::holdPreparingPlayers, 1L, 1L); }
            duel.holdPreparingPlayers();
            retry.retry(failure, this::prepare);
            return;
        }
        retry.clear();
        if (prepared) duel.setState(new SortingState(duel));
        else duel.forceEnd();
    }

    @Override public boolean handleArenaContainment(org.bukkit.event.player.PlayerMoveEvent event) {
        return duel.holdPreparationMovement(event);
    }

    @Override protected void onDisable() { retry.clear(); }
}
