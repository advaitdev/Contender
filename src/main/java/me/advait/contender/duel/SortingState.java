package me.advait.contender.duel;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerDropItemEvent;

public final class SortingState extends AbstractDuelState {
    private int remainingSeconds;

    public SortingState(Duel duel) { super(duel); }

    @Override
    protected void onEnable() {
        remainingSeconds = duel.getPreRoundDelay();
        duel.broadcastCountdown(remainingSeconds);
        runRepeating(() -> {
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                duel.captureInventories();
                duel.setState(new ActiveState(duel));
                return;
            }
            duel.broadcastCountdown(remainingSeconds);
            duel.broadcastSound(Duel.SoundType.COUNTDOWN_TICK);
        }, 20L, 20L);
    }

    @Override protected void onDisable() { duel.clearCountdown(); }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (owns(event.getPlayer())) event.setCancelled(true);
    }
}
