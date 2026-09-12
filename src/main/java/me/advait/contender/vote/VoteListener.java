package me.advait.contender.vote;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class VoteListener implements Listener {
    private final VoteManager manager;
    public VoteListener(VoteManager manager) { this.manager = manager; }
    @EventHandler public void quit(PlayerQuitEvent event) {
        var session = manager.getActiveSession();
        if (session != null) session.removeVote(event.getPlayer().getUniqueId());
    }
}
