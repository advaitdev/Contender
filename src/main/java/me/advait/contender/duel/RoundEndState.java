package me.advait.contender.duel;

import me.advait.contender.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class RoundEndState extends AbstractDuelState {
    private final DuelTeam winner;

    public RoundEndState(Duel duel, DuelTeam winner) {
        super(duel);
        this.winner = winner;
    }

    @Override
    protected void onEnable() {
        if (winner != null) winner.incrementScore();
        int roundsToWin = duel.getTotalRounds() / 2 + 1;
        if (winner != null && winner.getScore() >= roundsToWin
                || duel.getCurrentRound() >= duel.getTotalRounds()) {
            runLater(duel::endDuel, 1L);
            return;
        }
        if (winner != null) {
            duel.broadcastActionBar("<color:" + MessageUtil.PRIMARY + ">" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(winner.getName())
                    + " wins the round! <color:" + MessageUtil.SECONDARY + ">"
                    + duel.getTeam1().getScore() + " - " + duel.getTeam2().getScore() + "</color></color>");
            for (UUID uuid : winner.getPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) MessageUtil.playRoundWin(player);
            }
        } else {
            duel.broadcastActionBar("<color:" + MessageUtil.WARNING + ">Round ended in a tie!</color>");
        }
        duel.rollbackArena(guard(this::startCountdown));
    }

    private void startCountdown() {
        for (int seconds = 3; seconds >= 1; seconds--) {
            int remaining = seconds;
            runLater(() -> {
                duel.broadcastActionBar("<color:" + MessageUtil.WARNING + ">Next round in " + remaining + "...</color>");
                duel.broadcastSound(Duel.SoundType.COUNTDOWN_TICK);
            }, (4L - seconds) * 20L);
        }
        runLater(() -> duel.setState(new ActiveState(duel)), 80L);
    }
}
