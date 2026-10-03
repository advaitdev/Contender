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

import java.util.*;
import java.util.function.Consumer;

import static me.advait.contender.dialog.DialogPalette.*;

/** /vote for everyone, and the director's start and manage screens. */
public final class VoteDialogs {
    private static final int CELL = 170, NAV = 150, PER_PAGE = 12;
    private final Contender plugin;
    private final Dialogs dialogs;
    /** Where the director's Back goes when opened from a menu; null (from /vote) shows Close. */
    private final Consumer<Player> back;

    public VoteDialogs(Contender plugin) { this(plugin, null); }

    public VoteDialogs(Contender plugin, Consumer<Player> back) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
        this.back = back;
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
        boolean sittingOut = session.sittingOut(player.getUniqueId());
        boolean canVote = plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.CONTESTANT && !sittingOut;
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
                    : sittingOut ? "You're sitting out this vote." : !canVote ? "Only contestants can vote." : selected ? "Click again to take your vote back." : "Vote for " + candidate.name() + ".";
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
                    manage ? dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, CELL, (p, view) -> open(p, page, false))
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
                : canVote ? (session.candidate(player.getUniqueId()) == null && plugin.getRoleManager().isContestant(player.getUniqueId())
                        ? "You're safe this time. Pick a player, or click them again to undo." : "Pick a player. Click them again to undo.")
                : sittingOut ? "You're sitting out this vote, so you can only watch." : "You can watch the vote, but only contestants can vote."), 340));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 340));
        // Escape runs the footer button, so the footer stays a plain Close and Refresh goes in the grid.
        Dialogs.navigationRow(buttons,
                director && !manage ? dialogs.button(player, DialogIcon.SETTINGS.label("Edit Votes", TEXT), DialogText.muted("Change who voted for whom, for testing."), true, CELL, (p, view) -> editVotes(p, 0)) : null,
                dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, CELL, (p, view) -> open(p, page, manage)), CELL);
        dialogs.show(player, manage ? "Remove Players" : "Vote", body, List.of(), buttons, 2, NAV, director && !manage && back != null
                ? dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> back.accept(p)) : null);
    }

    // ---- Director: change who voted for whom ----------------------------------------------------

    private void editVotes(Player player, int requestedPage) {
        VoteSession session = plugin.getVotes().session();
        if (session == null) { player.closeDialog(); Dialogs.error(player, "No vote is running."); return; }
        List<java.util.Map.Entry<UUID, String>> voters = List.copyOf(session.voters().entrySet());
        int pages = Math.max(1, (voters.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (var voter : voters.subList(page * PER_PAGE, Math.min(voters.size(), (page + 1) * PER_PAGE))) {
            UUID choice = session.voteOf(voter.getKey());
            VoteSession.Candidate target = choice == null ? null : session.candidate(choice);
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(voter.getKey()), text(" " + voter.getValue() + "  →  ", TEXT),
                    target == null ? text("—", MUTED) : StringUtil.getPlayerHead(target.id()));
            String hint = target == null ? voter.getValue() + " hasn't voted." : voter.getValue() + " voted for " + target.name() + ".";
            buttons.add(dialogs.button(player, label, DialogText.lines(text(hint, TEXT), DialogText.muted("Click to change it.")), true, CELL,
                    (p, view) -> pickVote(p, voter.getKey(), page)));
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
        String who = session == null ? null : session.voters().get(voter);
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
        dialogs.show(player, who + "'s Vote", List.of(DialogBody.plainMessage(
                Component.textOfChildren(StringUtil.getPlayerHead(voter), text(" " + who + " votes for…", TEXT)), 340)), List.of(), buttons, 2, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> editVotes(p, returnPage)));
    }

    private VoteSession require(VoteSession session) {
        if (plugin.getVotes().session() != session || session.closed()) throw new IllegalStateException("That vote has ended.");
        return session;
    }

    /** Players the director has taken out of the ballot, or out of the vote completely. */
    private enum Standing {
        SAFE("Safe", "Votes, but can't be voted for.", SUCCESS), SITTING_OUT("Sitting Out", "Doesn't vote and can't be voted for.", MUTED);
        final String label, hint;
        final net.kyori.adventure.text.format.TextColor color;
        Standing(String label, String hint, net.kyori.adventure.text.format.TextColor color) { this.label = label; this.hint = hint; this.color = color; }
    }

    /** What the director filled in on Start a Vote, kept while they choose players. */
    private record StartDraft(int seconds, boolean live, boolean ceremony, VoteService.Elimination elimination, boolean anonymous,
                              VoteService.RoomMode rooms, Map<UUID, Standing> players) {
        static StartDraft defaults() {
            return new StartDraft(60, false, true, VoteService.Elimination.KILL, false, VoteService.RoomMode.RETURN, new LinkedHashMap<>());
        }
        Set<UUID> with(Standing standing) {
            Set<UUID> ids = new HashSet<>();
            players.forEach((id, value) -> { if (value == standing) ids.add(id); });
            return ids;
        }
    }

    /** The director's start screen. */
    public void start(Player player) { start(player, StartDraft.defaults()); }

    private void start(Player player, StartDraft draft) {
        List<DialogInput> inputs = new ArrayList<>(List.of(
                DialogInput.numberRange("seconds", DialogIcon.CLOCK.label("Length"), 10, 600).initial((float) draft.seconds()).step(10f).width(300)
                        .labelFormat("%s: %ss").build(),
                toggle("live", DialogIcon.EYE, "Show Counts While Voting", draft.live(), "Yes", "No, keep them secret"),
                toggle("ceremony", DialogIcon.STAR, "Results", draft.ceremony(), "Reveal ceremony", "Chat only"),
                DialogInput.singleOption("elimination", DialogIcon.SKULL.label("Voted-Out Player"), java.util.Arrays.stream(VoteService.Elimination.values())
                        .map(option -> Dialogs.option(option.name(), option.label, option == draft.elimination())).toList()).width(300).build(),
                toggle("anonymous", DialogIcon.PLAYERS, "Anonymous Votes", draft.anonymous(), "Yes", "No, show who everyone voted for")));
        // The rooms choice only appears once a voting or judge room is set.
        boolean rooms = plugin.getVotes().anyRoom();
        if (rooms) inputs.add(DialogInput.singleOption("rooms", DialogIcon.SPAWN.label("Rooms"), java.util.Arrays.stream(VoteService.RoomMode.values())
                .map(mode -> Dialogs.option(mode.name(), mode.label, mode == draft.rooms())).toList()).width(300).build());
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.PLAYERS.label("Choose Players", TEXT), DialogText.muted("Make players safe, or leave them out of the vote."), true, NAV,
                        (p, view) -> players(p, read(view, draft, rooms), 0)), null, NAV);
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> {
                    if (back != null) back.accept(p); else new TournamentDialogs(plugin).open(p);
                }),
                dialogs.button(player, DialogIcon.NEXT.label("Start Vote", ACCENT), null, true, NAV, (p, view) -> {
                    StartDraft chosen = read(view, draft, rooms);
                    plugin.getVotes().start(new VoteService.Options(chosen.seconds(), chosen.live(), chosen.ceremony(), chosen.elimination(),
                            rooms ? chosen.rooms() : VoteService.RoomMode.OFF, chosen.anonymous(), chosen.with(Standing.SAFE), chosen.with(Standing.SITTING_OUT)));
                    open(p);
                }), NAV);
        // Short lines, so Start Vote stays on screen at small GUI sizes.
        List<Component> lines = new ArrayList<>();
        for (Standing standing : Standing.values()) {
            List<String> names = draft.with(standing).stream().map(Bukkit::getPlayer).filter(Objects::nonNull).map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            if (!names.isEmpty()) lines.add(DialogText.detail(standing.label, String.join(", ", names), standing.color));
        }
        if (plugin.getVotes().stageLocation() == null) lines.add(text("Set a vote stage in Vote Setup for the lineup and spotlight.", WARNING));
        else if (lines.isEmpty()) lines.add(DialogText.muted("Contestants line up on the vote stage for the results."));
        dialogs.show(player, "Start a Vote", List.of(DialogBody.plainMessage(DialogText.lines(lines.toArray(Component[]::new)), 320)), inputs, buttons, 2, NAV, null);
    }

    private static StartDraft read(DialogResponseView view, StartDraft previous, boolean rooms) {
        return new StartDraft(Dialogs.number(view, "seconds", 10, 600), bool(view, "live"), bool(view, "ceremony"),
                VoteService.Elimination.valueOf(Dialogs.text(view, "elimination")), bool(view, "anonymous"),
                rooms ? VoteService.RoomMode.valueOf(Dialogs.text(view, "rooms")) : previous.rooms(), previous.players());
    }

    /** Click a contestant to cycle them: in the vote, safe, sitting out. */
    private void players(Player player, StartDraft draft, int requestedPage) {
        List<Player> contestants = Bukkit.getOnlinePlayers().stream().filter(p -> plugin.getRoleManager().isContestant(p.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).map(p -> (Player) p).toList();
        int pages = Math.max(1, (contestants.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (Player contestant : contestants.subList(page * PER_PAGE, Math.min(contestants.size(), (page + 1) * PER_PAGE))) {
            UUID id = contestant.getUniqueId();
            Standing standing = draft.players().get(id);
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(contestant), text(" " + contestant.getName(), standing == null ? TEXT : standing.color));
            if (standing != null) label = label.append(text("  " + standing.label, standing.color));
            Component hint = DialogText.lines(text(standing == null ? "In the vote." : standing.hint, TEXT), DialogText.muted("Click to change."));
            buttons.add(dialogs.button(player, label, hint, true, CELL, (p, view) -> {
                if (standing == null) draft.players().put(id, Standing.SAFE);
                else if (standing == Standing.SAFE) draft.players().put(id, Standing.SITTING_OUT);
                else draft.players().remove(id);
                players(p, draft, page);
            }));
        }
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, true, CELL, (p, view) -> players(p, draft, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, true, CELL, (p, view) -> players(p, draft, page + 1)) : null, CELL);
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), DialogText.muted("Your choices are kept."), true, CELL, (p, view) -> start(p, draft)),
                draft.players().isEmpty() ? null : dialogs.button(player, DialogIcon.REFRESH.label("Everyone In", TEXT), null, true, CELL, (p, view) -> {
                    draft.players().clear();
                    players(p, draft, page);
                }), CELL);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.lines(
                DialogText.muted(contestants.isEmpty() ? "No contestants are online." : "Click a player to change who's in this vote."),
                DialogText.detail(Standing.SAFE.label, "votes, but can't be voted for", Standing.SAFE.color),
                DialogText.detail(Standing.SITTING_OUT.label, "doesn't vote at all", Standing.SITTING_OUT.color)), 340));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 340));
        dialogs.show(player, "Choose Players", body, List.of(), buttons, 2, NAV, null);
    }

    private static DialogInput toggle(String key, DialogIcon icon, String label, boolean value, String yes, String no) {
        return DialogInput.singleOption(key, icon.label(label), List.of(Dialogs.option("true", yes, value), Dialogs.option("false", no, !value))).width(300).build();
    }

    private static boolean bool(DialogResponseView view, String key) { return Dialogs.text(view, key).equals("true"); }
}
