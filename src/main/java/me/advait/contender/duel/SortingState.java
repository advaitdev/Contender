package me.advait.contender.duel;

import me.advait.contender.util.MessageUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerDropItemEvent;

public final class SortingState extends AbstractDuelState {
    private int remainingSeconds;

    public SortingState(Duel duel) { super(duel); }

    @Override
    protected void onEnable() {
        remainingSeconds = duel.getPreRoundDelay();
        showSortingTime();
        runRepeating(() -> {
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                duel.captureInventories();
                duel.setState(new ActiveState(duel));
                return;
            }
            if (remainingSeconds <= 5) {
                duel.broadcastActionBar("<color:" + MessageUtil.WARNING + ">Starting in "
                        + "<color:" + MessageUtil.PRIMARY + ">" + remainingSeconds + "</color>...</color>");
            } else {
                showSortingTime();
            }
            duel.broadcastSound(Duel.SoundType.COUNTDOWN_TICK);
        }, 20L, 20L);
    }

    private void showSortingTime() {
        duel.broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">Sort your inventory! "
                + "<color:" + MessageUtil.SECONDARY + ">" + remainingSeconds + "s</color></color>");
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (owns(event.getPlayer())) event.setCancelled(true);
    }
}
