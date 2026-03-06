package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.gui.GUIHolder;
import me.advait.contender.gui.GUIType;
import me.advait.contender.gui.VoteGUI;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class VoteSession {

    private final Contender plugin;
    private final VoteManager manager;
    private int remainingSeconds;
    private final Map<UUID, UUID> voterToCandidate; // voter -> candidate
    private final Set<UUID> removedCandidates;
    private BukkitTask timerTask;
    private boolean ended = false;

    public VoteSession(Contender plugin, VoteManager manager, int seconds) {
        this.plugin = plugin;
        this.manager = manager;
        this.remainingSeconds = seconds;
        this.voterToCandidate = new HashMap<>();
        this.removedCandidates = new HashSet<>();
    }

    public void start() {
        MiniMessage mm = MiniMessage.miniMessage();
        Component announcement = mm.deserialize(
                MessageUtil.FONT_OPEN + "<color:" + MessageUtil.SECONDARY + ">A vote has started! " +
                "<color:" + MessageUtil.PRIMARY + ">Use /vote to participate!</color></color>" + MessageUtil.FONT_CLOSE);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(announcement);
            MessageUtil.playSuccess(p);
        }

        broadcastTimerActionBar();

        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                timerTask.cancel();
                timerTask = null;
                end();
                return;
            }
            if (remainingSeconds <= 10) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    MessageUtil.playCountdownTick(p);
                }
            }
            broadcastTimerActionBar();
        }, 20L, 20L);
    }

    private void broadcastTimerActionBar() {
        int m = remainingSeconds / 60;
        int s = remainingSeconds % 60;
        String timeStr = String.format("%02d:%02d", m, s);
        String timeColor = remainingSeconds <= 10 ? MessageUtil.ERROR : MessageUtil.WARNING;
        String message = "<color:" + MessageUtil.SECONDARY + ">Vote</color> " +
                "<color:" + MessageUtil.MUTED + ">|</color> " +
                "<color:" + timeColor + ">" + timeStr + "</color>";
        for (Player p : Bukkit.getOnlinePlayers()) {
            MessageUtil.sendActionBar(p, message);
        }
    }

    public void vote(UUID voter, UUID candidate) {
        voterToCandidate.put(voter, candidate);
        refreshOpenGUIs();
    }

    public void removeVote(UUID voter) {
        voterToCandidate.remove(voter);
        refreshOpenGUIs();
    }

    public void removeCandidate(UUID candidate) {
        removedCandidates.add(candidate);
        voterToCandidate.entrySet().removeIf(e -> e.getValue().equals(candidate));
        refreshOpenGUIs();
    }

    public int getVoteCount(UUID candidate) {
        int count = 0;
        for (UUID c : voterToCandidate.values()) {
            if (c.equals(candidate)) count++;
        }
        return count;
    }

    public UUID getVoteFor(UUID voter) {
        return voterToCandidate.get(voter);
    }

    public boolean isRemoved(UUID candidate) {
        return removedCandidates.contains(candidate);
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public void refreshOpenGUIs() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var topInv = player.getOpenInventory().getTopInventory();
            if (topInv.getHolder() instanceof GUIHolder holder && holder.getType() == GUIType.VOTE_GUI) {
                VoteGUI.populate(topInv, this, player, plugin.getSpectatorManager());
            }
        }
    }

    public void end() {
        if (ended) return;
        ended = true;

        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        closeAllGUIs();
        announceResults();
        manager.onSessionEnd();
    }

    private void closeAllGUIs() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var topInv = player.getOpenInventory().getTopInventory();
            if (topInv.getHolder() instanceof GUIHolder holder && holder.getType() == GUIType.VOTE_GUI) {
                player.closeInventory();
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(Component.empty());
        }
    }

    private void announceResults() {
        MiniMessage mm = MiniMessage.miniMessage();

        // Collect eligible candidates currently online
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!plugin.getSpectatorManager().isEventSpectator(uuid) && !removedCandidates.contains(uuid)) {
                counts.put(uuid, getVoteCount(uuid));
            }
        }

        if (counts.isEmpty()) {
            Component msg = mm.deserialize(MessageUtil.FONT_OPEN +
                    "<color:" + MessageUtil.MUTED + ">The vote ended with no candidates.</color>" +
                    MessageUtil.FONT_CLOSE);
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
            return;
        }

        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int maxVotes = sorted.get(0).getValue();
        List<UUID> winners = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : sorted) {
            if (entry.getValue() == maxVotes) winners.add(entry.getKey());
        }

        Component announcement;
        if (winners.size() > 1) {
            announcement = mm.deserialize(MessageUtil.FONT_OPEN +
                    "<color:" + MessageUtil.WARNING + ">The vote ended in a tie!</color>" +
                    MessageUtil.FONT_CLOSE);
        } else {
            UUID winnerUuid = winners.get(0);
            Player winner = Bukkit.getPlayer(winnerUuid);
            String winnerName = winner != null ? winner.getName()
                    : Objects.requireNonNullElse(Bukkit.getOfflinePlayer(winnerUuid).getName(), "Unknown");

            Component head = winner != null ? StringUtil.getPlayerHead(winner) : Component.empty();
            Component text = mm.deserialize(MessageUtil.FONT_OPEN +
                    "<color:" + MessageUtil.PRIMARY + ">" + winnerName + "</color>" +
                    "<color:" + MessageUtil.MUTED + "> wins the vote with </color>" +
                    "<color:" + MessageUtil.SECONDARY + ">" + maxVotes + " vote" + (maxVotes != 1 ? "s" : "") + "</color>" +
                    "<color:" + MessageUtil.MUTED + ">!</color>" +
                    MessageUtil.FONT_CLOSE);
            announcement = Component.empty().append(head).appendSpace().append(text);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(announcement);
            MessageUtil.playSuccess(p);
        }
    }
}
