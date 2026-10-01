package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * Community votes: numbered badges above every candidate while the vote runs, a timer in the lobby,
 * and a reveal ceremony at the end. Votes never change roles unless the director asks for it.
 */
public final class VoteService extends Module {
    public record Options(int seconds, boolean liveCounts, boolean ceremony, boolean eliminate) { }

    private VoteSession session;
    private VoteBadges badges;
    private VoteTimerDisplay timer;
    private VoteReveal reveal;
    private Options options;

    public VoteService(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        badges = new VoteBadges(plugin);
        timer = new VoteTimerDisplay(plugin);
        tasks.repeat(1, 1, () -> badges.follow());
        tasks.repeat(20, 20, this::second);
    }

    @Override protected void onDisable() {
        if (reveal != null) reveal.forceStop("");
        session = null;
        badges.clear();
        timer.hide();
    }

    public boolean isActive() { return session != null; }
    public VoteSession session() { return session; }
    public boolean revealing() { return reveal != null; }

    public void start(Options chosen) {
        if (session != null) throw new IllegalStateException("A vote is already running.");
        if (reveal != null) throw new IllegalStateException("Wait for the results to finish.");
        if (chosen.seconds() < 10 || chosen.seconds() > 3600) throw new IllegalArgumentException("Choose a length from 10 seconds to 60 minutes.");
        List<Map.Entry<UUID, String>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.CONTESTANT) {
                players.add(Map.entry(player.getUniqueId(), player.getName()));
            }
        }
        players.sort(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER));
        if (players.size() < 2) throw new IllegalStateException("At least two contestants must be online to vote.");
        options = chosen;
        session = new VoteSession(VoteSession.number(players), chosen.liveCounts(), System.currentTimeMillis() + chosen.seconds() * 1000L);
        if (plugin.getTournaments().current() != null && plugin.getTournaments().current().isRunning()) plugin.getTournaments().pause();
        for (VoteSession.Candidate candidate : session.candidates()) badges.show(candidate);
        var theme = plugin.getThemes().current();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Msg.title(player, Component.text("Vote", theme.primary()), Msg.text("Use /vote to choose a number", DialogPalette.MUTED), 8, 50, 12);
            Sounds.ANNOUNCE.play(player);
        }
        Msg.broadcast(Msg.text("A vote has started. ", DialogPalette.ACCENT).append(Msg.text("Use /vote or /vote <number>.", DialogPalette.TEXT)));
        second();
    }

    private void second() {
        if (session == null) return;
        int left = (int) Math.max(0, Math.ceil((session.endsAt() - System.currentTimeMillis()) / 1000.0));
        timer.update(left);
        Component bar = Msg.text("Vote  ", DialogPalette.ACCENT).append(Msg.text(String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60),
                left <= 10 ? DialogPalette.DANGER : DialogPalette.TEXT));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getRegistry().isPlaying(player.getUniqueId())) continue; // Don't cover a fight's action bar.
            player.sendActionBar(bar);
            if (left <= 5 && left > 0) Sounds.TICK.play(player);
        }
        if (session.liveCounts()) for (VoteSession.Candidate candidate : session.candidates()) badges.showCount(candidate, session.votesFor(candidate.id()));
        if (left <= 0) end();
    }

    public void vote(Player voter, VoteSession.Candidate target) {
        VoteSession current = require();
        if (plugin.getRoleManager().getRole(voter.getUniqueId()) != PlayerRole.CONTESTANT) throw new IllegalStateException("Only contestants can vote.");
        current.vote(voter.getUniqueId(), target);
        Sounds.CLICK.play(voter);
        if (current.liveCounts()) badges.showCount(target, current.votesFor(target.id()));
    }

    public void unvote(Player voter) { require().unvote(voter.getUniqueId()); }

    public void removeCandidate(UUID candidate) {
        VoteSession current = require();
        current.remove(candidate);
        badges.remove(candidate, true);
    }

    private VoteSession require() {
        if (session == null) throw new IllegalStateException("No vote is running.");
        return session;
    }

    /** Closes voting and plays the results. */
    public void end() {
        VoteSession closing = session;
        if (closing == null) return;
        closing.close();
        session = null;
        timer.hide();
        for (Player player : Bukkit.getOnlinePlayers()) if (!plugin.getRegistry().isPlaying(player.getUniqueId())) player.sendActionBar(Component.empty());
        if (options.ceremony()) {
            reveal = new VoteReveal(plugin, this, closing, badges, options.eliminate());
            reveal.play();
        } else {
            announce(closing);
            tasks.later(200, badges::clear);
        }
    }

    private void announce(VoteSession closed) {
        List<VoteSession.Candidate> leaders = closed.leaders();
        for (VoteSession.Tally tally : closed.results()) badges.showCount(tally.candidate(), tally.votes());
        if (leaders.isEmpty()) { Msg.broadcast(Msg.text("The vote ended with no votes cast.", DialogPalette.MUTED)); return; }
        int votes = closed.votesFor(leaders.getFirst().id());
        String names = String.join(" & ", leaders.stream().map(VoteSession.Candidate::name).toList());
        Msg.broadcast(Msg.text(leaders.size() > 1 ? "Tied: " : "Most votes: ", DialogPalette.MUTED)
                .append(Msg.text(names, DialogPalette.ACCENT)).append(Msg.text(" (" + votes + ")", DialogPalette.MUTED)));
        Sounds.ANNOUNCE.playAll();
    }

    void revealFinished(VoteReveal finished) {
        if (reveal != finished) return;
        reveal = null;
        badges.clear();
    }

    /** Stops a vote or a reveal without results. */
    public void cancel() {
        if (session != null) { session.close(); session = null; }
        if (reveal != null) reveal.forceStop("");
        reveal = null;
        badges.clear();
        timer.hide();
    }

    // ---- Placement -----------------------------------------------------------------------------

    public void placeTimer(Player player) { timer.place(player); }
    public void removeTimer() { timer.remove(); }
    public boolean timerPlaced() { return timer.placed(); }

    public void setStage(Location location) {
        if (plugin.getArenas().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Set the vote stage outside the arena world.");
        var config = plugin.getConfig();
        config.set("votes.stage.world", location.getWorld().getName());
        config.set("votes.stage.x", location.getBlockX() + 0.5);
        config.set("votes.stage.y", location.getY());
        config.set("votes.stage.z", location.getBlockZ() + 0.5);
        plugin.saveConfig();
    }

    public void clearStage() {
        plugin.getConfig().set("votes.stage", null);
        plugin.saveConfig();
    }

    public Location stageLocation() {
        var config = plugin.getConfig();
        String name = config.getString("votes.stage.world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) return null;
        return new Location(world, config.getDouble("votes.stage.x"), config.getDouble("votes.stage.y"), config.getDouble("votes.stage.z"));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (session != null) session.unvote(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (session == null) return;
        VoteSession.Candidate candidate = session.candidate(event.getPlayer().getUniqueId());
        if (candidate != null) badges.show(candidate);
    }
}
