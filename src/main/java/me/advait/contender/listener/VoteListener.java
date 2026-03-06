package me.advait.contender.listener;

import me.advait.contender.SpectatorManager;
import me.advait.contender.gui.GUIHolder;
import me.advait.contender.gui.GUIType;
import me.advait.contender.gui.VoteGUI;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.vote.VoteManager;
import me.advait.contender.vote.VoteSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

public class VoteListener implements Listener {

    private final VoteManager voteManager;
    private final SpectatorManager spectatorManager;

    public VoteListener(VoteManager voteManager, SpectatorManager spectatorManager) {
        this.voteManager = voteManager;
        this.spectatorManager = spectatorManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof GUIHolder holder)) return;
        if (holder.getType() != GUIType.VOTE_GUI) return;

        event.setCancelled(true);

        VoteSession session = voteManager.getActiveSession();
        if (session == null) {
            player.closeInventory();
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= 45) return; // ignore filler row and player inventory

        List<Player> candidates = VoteGUI.getCandidates(session, spectatorManager);
        if (rawSlot >= candidates.size()) return;

        Player candidate = candidates.get(rawSlot);
        UUID candidateUuid = candidate.getUniqueId();

        if (event.isRightClick() && player.hasPermission("contender.master")) {
            session.removeCandidate(candidateUuid);
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">" + candidate.getName() + " removed from the vote</color>");
            MessageUtil.playClick(player);
            return;
        }

        if (event.isLeftClick()) {
            if (spectatorManager.isEventSpectator(player.getUniqueId())) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">Spectators cannot vote!</color>");
                return;
            }

            UUID currentVote = session.getVoteFor(player.getUniqueId());
            if (candidateUuid.equals(currentVote)) {
                session.removeVote(player.getUniqueId());
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.MUTED + ">Vote removed</color>");
            } else {
                session.vote(player.getUniqueId(), candidateUuid);
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.PRIMARY + ">Voted for " + candidate.getName() + "!</color>");
            }
            MessageUtil.playClick(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GUIHolder holder)) return;
        if (holder.getType() != GUIType.VOTE_GUI) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        VoteSession session = voteManager.getActiveSession();
        if (session != null) {
            session.removeVote(event.getPlayer().getUniqueId());
        }
    }
}
