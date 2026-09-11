package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.duel.DuelManager;

public class VoteManager {

    private final Contender plugin;
    private final DuelManager duelManager;
    private VoteSession activeSession;

    public VoteManager(Contender plugin, DuelManager duelManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
    }

    /**
     * Starts a new vote. Returns false if one is already active.
     */
    public boolean startVote(int seconds) {
        if (activeSession != null) return false;
        plugin.getTournamentManager().pause();
        duelManager.cleanup();
        activeSession = new VoteSession(plugin, this, seconds);
        activeSession.start();
        return true;
    }

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
    }
}
