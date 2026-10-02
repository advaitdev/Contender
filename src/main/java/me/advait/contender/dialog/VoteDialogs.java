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
import java.util.UUID;
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
        // Counts only with Show Counts While Voting, for everyone; directors see the details in Edit Votes.
        boolean showCounts = session.liveCounts();
        List<VoteSession.Candidate> candidates = session.candidates();
        int pages = Math.max(1, (candidates.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        var mine = session.voteOf(player.getUniqueId());
        for (VoteSession.Candidate candidate : candidates.subList(page * PER_PAGE, Math.min(candidates.size(), (page + 1) * PER_PAGE))) {
            Player online = Bukkit.getPlayer(candidate.id());
            Component head = online == null ? StringUtil.getPlayerHead(candidate.id()) : StringUtil.getPlayerHead(online);
            boolean selected = candidate.id().equals(mine), self = candidate.id().equals(player.getUniqueId());
            Component label = Component.textOfChildren(head, text(" " + candidate.name(), manage ? DANGER : self ? MUTED : TEXT));
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
                : canVote ? "Pick a player. Click them again to undo." : "You can watch the vote, but only contestants can vote."), 340));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 340));
        // Escape runs the footer button, so the footer stays a plain Close and Refresh goes in the grid.
        Dialogs.navigationRow(buttons,
                director && !manage ? dialogs.button(player, DialogIcon.SETTINGS.label("Edit Votes", TEXT), DialogText.muted("Change who voted for whom, for testing."), true, CELL, (p, view) -> editVotes(p, 0)) : null,
                dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, CELL, (p, view) -> open(p, page, manage)), CELL);
        dialogs.show(player, manage ? "Remove Players" : "Vote", body, List.of(), buttons, 2, NAV, null);
    }

    // ---- Director: change who voted for whom ----------------------------------------------------

    private void editVotes(Player player, int requestedPage) {
        VoteSession session = plugin.getVotes().session();
        if (session == null) { player.closeDialog(); Dialogs.error(player, "No vote is running."); return; }
        List<VoteSession.Candidate> voters = session.candidates();
        int pages = Math.max(1, (voters.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (VoteSession.Candidate voter : voters.subList(page * PER_PAGE, Math.min(voters.size(), (page + 1) * PER_PAGE))) {
            UUID choice = session.voteOf(voter.id());
            VoteSession.Candidate target = choice == null ? null : session.candidate(choice);
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(voter.id()), text(" " + voter.name() + "  →  ", TEXT),
                    target == null ? text("—", MUTED) : StringUtil.getPlayerHead(target.id()));
            String hint = target == null ? voter.name() + " hasn't voted." : voter.name() + " voted for " + target.name() + ".";
            buttons.add(dialogs.button(player, label, DialogText.lines(text(hint, TEXT), DialogText.muted("Click to change it.")), true, CELL,
                    (p, view) -> pickVote(p, voter.id(), page)));
        }
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, true, CELL, (p, view) -> editVotes(p, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, true, CELL, (p, view) -> editVotes(p, page + 1)) : null, CELL);
        List<String> totals = session.results().stream().filter(t -> t.votes() > 0).map(t -> t.candidate().name() + " " + t.votes()).toList();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.lines(DialogText.muted("Change who voted for whom. Players aren't told."),
                DialogText.detail("Votes", totals.isEmpty() ? "None yet" : String.join(" · ", totals))), 340));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 340));
        dialogs.show(player, "Edit Votes", body, List.of(), buttons, 2, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> open(p)));
    }

    private void pickVote(Player player, UUID voter, int returnPage) {
        VoteSession session = plugin.getVotes().session();
        VoteSession.Candidate who = session == null ? null : session.candidate(voter);
        if (who == null) { editVotes(player, returnPage); return; }
        UUID current = session.voteOf(voter);
        List<ActionButton> buttons = new ArrayList<>();
        for (VoteSession.Candidate candidate : session.candidates()) {
            if (candidate.id().equals(voter)) continue;
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(candidate.id()), text(" " + candidate.name(), TEXT));
            if (candidate.id().equals(current)) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            buttons.add(dialogs.button(player, label, null, true, CELL, (p, view) -> {
                require(session);
                plugin.getVotes().setVote(voter, candidate.id());
                editVotes(p, returnPage);
            }));
        }
        Component none = text("No Vote", MUTED);
        if (current == null) none = none.append(Component.space()).append(DialogIcon.SAVE.sprite());
        buttons.add(dialogs.button(player, none, DialogText.muted("Take their vote away."), true, CELL, (p, view) -> {
            require(session);
            plugin.getVotes().setVote(voter, null);
            editVotes(p, returnPage);
        }));
        dialogs.show(player, who.name() + "'s Vote", List.of(DialogBody.plainMessage(
                Component.textOfChildren(StringUtil.getPlayerHead(voter), text(" " + who.name() + " votes for…", TEXT)), 340)), List.of(), buttons, 2, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> editVotes(p, returnPage)));
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
                DialogInput.singleOption("elimination", DialogIcon.SKULL.label("Voted-Out Player"), java.util.Arrays.stream(VoteService.Elimination.values())
                        .map(option -> Dialogs.option(option.name(), option.label, option == VoteService.Elimination.KILL)).toList()).width(300).build(),
                toggle("anonymous", DialogIcon.PLAYERS, "Anonymous Votes", true, "Yes", "No, show who everyone voted for")));
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
                            bool(view, "live"), bool(view, "ceremony"), VoteService.Elimination.valueOf(Dialogs.text(view, "elimination")), mode,
                            bool(view, "anonymous")));
                    open(p);
                }), NAV);
        boolean stage = plugin.getVotes().stageLocation() != null;
        // One line, so Start Vote stays on screen at small GUI sizes.
        dialogs.show(player, "Start a Vote", List.of(DialogBody.plainMessage(stage
                ? DialogText.muted("Contestants line up on the vote stage for the results.")
                : text("Set a vote stage in Vote Setup for the lineup and spotlight.", WARNING), 320)), inputs, buttons, 2, NAV, null);
    }

    private static DialogInput toggle(String key, DialogIcon icon, String label, boolean value, String yes, String no) {
        return DialogInput.singleOption(key, icon.label(label), List.of(Dialogs.option("true", yes, value), Dialogs.option("false", no, !value))).width(300).build();
    }

    private static boolean bool(DialogResponseView view, String key) { return Dialogs.text(view, key).equals("true"); }
}
