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
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, false, CELL, (p, view) -> open(p, page - 1, manage)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, false, CELL, (p, view) -> open(p, page + 1, manage)) : null, CELL);
        if (director) {
            Dialogs.navigationRow(buttons,
                    manage ? dialogs.button(player, DialogIcon.BACK.label("Done", MUTED), null, true, CELL, (p, view) -> open(p, page, false))
                            : dialogs.button(player, DialogIcon.SETTINGS.label("Remove Players", TEXT), DialogText.muted("Take someone off the ballot."), true, CELL, (p, view) -> open(p, 0, true)),
                    dialogs.button(player, DialogIcon.STAR.label("End Vote Now", ACCENT), DialogText.muted("Close voting and show the results."), true, CELL, (p, view) -> {
                        require(session);
                        p.closeDialog();
                        plugin.getVotes().end();
                    }), CELL);
        }
        long seconds = Math.max(0, (session.endsAt() - System.currentTimeMillis()) / 1000);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.lines(
                DialogText.detail("Time left", String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), seconds <= 10 ? DANGER : TEXT),
                DialogText.detail("Votes cast", Integer.toString(session.totalVotes()))), 340));
        body.add(DialogBody.plainMessage(DialogText.muted(manage ? "Click a player to remove them from this vote."
                : canVote ? "Pick someone's number. Click it again to undo." : "You can watch the vote, but only contestants can vote."), 340));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 340));
        // Escape runs the footer button, so the footer stays a plain Close and Refresh goes in the grid.
        Dialogs.navigationRow(buttons, null, dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, CELL, (p, view) -> open(p, page, manage)), CELL);
        dialogs.show(player, manage ? "Remove Players" : "Vote", body, List.of(), buttons, 2, NAV, null);
    }

    private VoteSession require(VoteSession session) {
        if (plugin.getVotes().session() != session || session.closed()) throw new IllegalStateException("That vote has ended.");
        return session;
    }

    /** The director's start screen. */
    public void start(Player player) {
        List<DialogInput> inputs = new ArrayList<>(List.of(
                DialogInput.numberRange("seconds", DialogIcon.CLOCK.label("Length"), 10, 600).initial(60f).step(10f).width(300)
                        .labelFormat("%s: %ss").build(),
                toggle("live", DialogIcon.EYE, "Show Counts While Voting", false, "Yes", "No, keep them secret"),
                toggle("ceremony", DialogIcon.STAR, "Results", true, "Reveal ceremony", "Chat only"),
                toggle("eliminate", DialogIcon.SKULL, "Voted-Out Player", false, "Becomes a spectator", "Stays a contestant")));
        // The rooms choice only appears once a voting or judge room is set.
        boolean rooms = plugin.getVotes().anyRoom();
        if (rooms) inputs.add(DialogInput.singleOption("rooms", DialogIcon.SPAWN.label("Rooms"), java.util.Arrays.stream(VoteService.RoomMode.values())
                .map(mode -> Dialogs.option(mode.name(), mode.label, mode == VoteService.RoomMode.RETURN)).toList()).width(300).build());
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).open(p)),
                dialogs.button(player, DialogIcon.NEXT.label("Start Vote", ACCENT), null, true, NAV, (p, view) -> {
                    VoteService.RoomMode mode = rooms ? VoteService.RoomMode.valueOf(Dialogs.text(view, "rooms")) : VoteService.RoomMode.OFF;
                    plugin.getVotes().start(new VoteService.Options(Dialogs.number(view, "seconds", 10, 600),
                            bool(view, "live"), bool(view, "ceremony"), bool(view, "eliminate"), mode));
                    open(p);
                }), NAV);
        boolean stage = plugin.getVotes().stageLocation() != null;
        // One line, so Start Vote stays on screen at small GUI sizes.
        dialogs.show(player, "Start a Vote", List.of(DialogBody.plainMessage(stage
                ? DialogText.muted("Each contestant gets a number over their head.")
                : text("Set a vote stage in Vote Setup for the circle reveal.", WARNING), 320)), inputs, buttons, 2, NAV, null);
    }

    private static DialogInput toggle(String key, DialogIcon icon, String label, boolean value, String yes, String no) {
        return DialogInput.singleOption(key, icon.label(label), List.of(Dialogs.option("true", yes, value), Dialogs.option("false", no, !value))).width(300).build();
    }

    private static boolean bool(DialogResponseView view, String key) { return Dialogs.text(view, key).equals("true"); }
}
