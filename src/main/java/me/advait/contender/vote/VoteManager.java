package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.duel.DuelManager;

public class VoteManager {

    private final Contender plugin;
    private final DuelManager duelManager;
    private VoteSession activeSession;
    private final VoteTimerDisplay timer;

    public VoteManager(Contender plugin, DuelManager duelManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        timer = new VoteTimerDisplay(plugin);
    }

    /**
     * Starts a new vote. Returns false if one is already active.
     */
    public boolean startVote(int seconds) {
        if (activeSession != null) return false;
        if (plugin.getMinigameManager() != null && plugin.getMinigameManager().active()) throw new IllegalStateException("Finish or cancel the minigame before starting a vote.");
        if (plugin.getRaceManager() != null && plugin.getRaceManager().active()) throw new IllegalStateException("Finish or cancel the race before starting a vote.");
        plugin.getTournamentManager().pause();
        duelManager.cleanup();
        activeSession = new VoteSession(plugin, this, seconds);
        activeSession.start();
        return true;
    }

    public VoteTimerDisplay timer() { return timer; }

    public void endVote() {
        if (activeSession != null) {
            activeSession.end();
        }
    }

    public boolean isVoteActive() {
        return activeSession != null;
    }

    public VoteSession getActiveSession() {
        return activeSession;
    }

    /** Called by VoteSession when the vote finishes. */
    void onSessionEnd() {
        activeSession = null;
        timer.hide();
    }
}
