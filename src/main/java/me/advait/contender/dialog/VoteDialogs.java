package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.util.StringUtil;
import me.advait.contender.vote.VoteService;
import me.advait.contender.vote.VoteSession;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static me.advait.contender.dialog.DialogPalette.*;

/** /vote for everyone, and the director's start and manage screens. */
public final class VoteDialogs {
    private static final int CELL = 170, NAV = 150, PER_PAGE = 12;
    private final Contender plugin;
    private final Dialogs dialogs;

    public VoteDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    public void open(Player player) { open(player, 0, false); }

    private void open(Player player, int requestedPage, boolean manage) {
        VoteService votes = plugin.getVotes();
        VoteSession session = votes.session();
        if (session == null) {
            if (player.hasPermission("contender.master")) { start(player); return; }
            player.closeDialog();
            Dialogs.error(player, "No vote is running.");
            return;
        }
        boolean director = player.hasPermission("contender.master");
        boolean canVote = plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.CONTESTANT;
        boolean showCounts = session.liveCounts() || director;
        List<VoteSession.Candidate> candidates = session.candidates();
        int pages = Math.max(1, (candidates.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        var mine = session.voteOf(player.getUniqueId());
        for (VoteSession.Candidate candidate : candidates.subList(page * PER_PAGE, Math.min(candidates.size(), (page + 1) * PER_PAGE))) {
            Player online = Bukkit.getPlayer(candidate.id());
            Component head = online == null ? StringUtil.getPlayerHead(candidate.id()) : StringUtil.getPlayerHead(online);
            boolean selected = candidate.id().equals(mine), self = candidate.id().equals(player.getUniqueId());
            Component label = Component.textOfChildren(text(candidate.number() + "  ", ACCENT), head,
                    text(" " + candidate.name(), manage ? DANGER : self ? MUTED : TEXT));
            if (showCounts) label = label.append(text("  " + session.votesFor(candidate.id()), MUTED));
            if (selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            String hint = manage ? "Remove " + candidate.name() + " from this vote." : self ? "You can't vote for yourself."
                    : !canVote ? "Only contestants can vote." : selected ? "Click again to take your vote back." : "Vote for " + candidate.name() + ".";
            if (!manage && (self || !canVote)) {
                buttons.add(ActionButton.builder(regular(label)).tooltip(DialogText.muted(hint)).width(CELL).build());
                continue;
            }
            buttons.add(dialogs.button(player, label, DialogText.muted(hint), manage, CELL, (p, view) -> {
                VoteSession current = require(session);
                if (manage) plugin.getVotes().removeCandidate(candidate.id());
                else if (candidate.id().equals(current.voteOf(p.getUniqueId()))) plugin.getVotes().unvote(p);
                else plugin.getVotes().vote(p, current.candidate(candidate.id()));
                open(p, page, manage);
            }));
        }
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, false, NAV, (p, view) -> open(p, page - 1, manage)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, false, NAV, (p, view) -> open(p, page + 1, manage)) : null, NAV);
        if (director) {
            Dialogs.navigationRow(buttons,
                    manage ? dialogs.button(player, DialogIcon.BACK.label("Done", MUTED), null, true, NAV, (p, view) -> open(p, page, false))
                            : dialogs.button(player, DialogIcon.SETTINGS.label("Remove Players", TEXT), DialogText.muted("Take someone off the ballot."), true, NAV, (p, view) -> open(p, 0, true)),
                    dialogs.button(player, DialogIcon.STAR.label("End Vote Now", ACCENT), DialogText.muted("Close voting and show the results."), true, NAV, (p, view) -> {
                        require(session);
                        p.closeDialog();
                        plugin.getVotes().end();
                    }), NAV);
        }
        long seconds = Math.max(0, (session.endsAt() - System.currentTimeMillis()) / 1000);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.lines(
                DialogText.detail("Time left", String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), seconds <= 10 ? DANGER : TEXT),
                DialogText.detail("Votes cast", Integer.toString(session.totalVotes()))), 340));
        body.add(DialogBody.plainMessage(DialogText.muted(manage ? "Click a player to remove them from this vote."
                : canVote ? "Pick the number floating over someone's head. Click again to undo." : "You can watch the vote, but only contestants can vote."), 340));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 340));
        dialogs.show(player, manage ? "Remove Players" : "Vote", body, List.of(), buttons, 2, NAV,
                dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, NAV, (p, view) -> open(p, page, manage)));
    }

    private VoteSession require(VoteSession session) {
        if (plugin.getVotes().session() != session || session.closed()) throw new IllegalStateException("That vote has ended.");
        return session;
    }

    /** The director's start screen. */
    public void start(Player player) {
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("seconds", DialogIcon.CLOCK.label("Length"), 10, 600).initial(60f).step(10f).width(300)
                        .labelFormat("%s: %ss").build(),
                toggle("live", DialogIcon.EYE, "Show Counts While Voting", false, "Yes", "No, keep them secret"),
                toggle("ceremony", DialogIcon.STAR, "Results", true, "Reveal ceremony", "Chat only"),
                toggle("eliminate", DialogIcon.SKULL, "Voted-Out Player", false, "Becomes a spectator", "Stays a contestant"));
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).open(p)),
                dialogs.button(player, DialogIcon.NEXT.label("Start Vote", ACCENT), null, true, NAV, (p, view) -> {
                    plugin.getVotes().start(new VoteService.Options(Dialogs.number(view, "seconds", 10, 600),
                            bool(view, "live"), bool(view, "ceremony"), bool(view, "eliminate")));
                    open(p);
                }), NAV);
        boolean stage = plugin.getVotes().stageLocation() != null;
        dialogs.show(player, "Start a Vote", List.of(DialogBody.plainMessage(DialogText.lines(
                DialogText.muted("Every online contestant gets a number over their head."),
                DialogText.muted(stage ? "For the results, candidates gather in a circle on the vote stage."
                        : "Tip: set a vote stage in Setup Tools → Board & Lobby for the circle reveal.")), 320)), inputs, buttons, 2, NAV, null);
    }

    private static DialogInput toggle(String key, DialogIcon icon, String label, boolean value, String yes, String no) {
        return DialogInput.singleOption(key, icon.label(label), List.of(Dialogs.option("true", yes, value), Dialogs.option("false", no, !value))).width(300).build();
    }

    private static boolean bool(DialogResponseView view, String key) { return Dialogs.text(view, key).equals("true"); }
}
