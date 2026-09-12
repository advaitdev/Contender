package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import me.advait.contender.Contender;
import me.advait.contender.util.StringUtil;
import me.advait.contender.vote.VoteSession;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static me.advait.contender.dialog.DialogPalette.*;

public final class VoteDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public VoteDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) {
        var session = plugin.getVoteManager().getActiveSession();
        if (session == null) { Dialogs.error(player, "No vote is running."); return; }
        open(player, session, 0, false);
    }
    private void requireCurrent(Player player, VoteSession session) {
        if (plugin.getVoteManager().getActiveSession() != session || session.isEnded()) {
            player.closeDialog(); throw new IllegalStateException("That vote has ended.");
        }
    }
    private void open(Player player, VoteSession session, int requestedPage, boolean manage) {
        requireCurrent(player, session);
        if (manage && !player.hasPermission("contender.master")) throw new IllegalStateException("You don't have permission to manage votes.");
        var candidates = plugin.getServer().getOnlinePlayers().stream().filter(p -> session.isCandidate(p.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName)).toList();
        int pages = Math.max(1, (candidates.size() + 7) / 8), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (Player candidate : candidates.subList(page * 8, Math.min(candidates.size(), page * 8 + 8))) {
            var id = candidate.getUniqueId();
            boolean selected = id.equals(session.getVoteFor(player.getUniqueId()));
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(candidate), text(" " + candidate.getName(), manage ? DANGER : TEXT),
                    text("  " + session.getVoteCount(id), ACCENT));
            if (selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            buttons.add(dialogs.button(player, label, DialogText.muted(manage ? "Remove this candidate and votes for them."
                    : selected ? "Click again to remove your vote." : "Vote for " + candidate.getName() + "."), manage, 220, (p, view) -> {
                requireCurrent(p, session);
                if (manage) session.removeCandidate(id);
                else if (id.equals(session.getVoteFor(p.getUniqueId()))) session.removeVote(p.getUniqueId());
                else session.vote(p.getUniqueId(), id);
                open(p, session, page, manage);
            }));
        }
        ActionButton manageButton = manage
                ? dialogs.button(player, DialogIcon.BACK.label("Back to Vote", MUTED), null, false, 220, (p, view) -> open(p, session, 0, false))
                : player.hasPermission("contender.master")
                ? dialogs.button(player, DialogIcon.SETTINGS.label("Manage Candidates"), null, true, 220, (p, view) -> open(p, session, 0, true)) : null;
        Dialogs.navigationRow(buttons, manageButton,
                dialogs.button(player, DialogIcon.REFRESH.label("Refresh"), null, false, 220, (p, view) -> open(p, session, page, manage)));
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK, "Previous Page", false, (p, view) -> open(p, session, page - 1, manage)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT, "Next Page", false, (p, view) -> open(p, session, page + 1, manage)) : null);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.detail("Time left", session.getRemainingSeconds() + " seconds"), 320));
        body.add(DialogBody.plainMessage(DialogText.muted(manage ? "Choose a candidate to remove from this vote."
                : plugin.getRoleManager().isContestant(player.getUniqueId()) ? "Choose a player. Click them again to undo your vote." : "Only contestants can vote. You can view the results here."), 320));
        if (candidates.isEmpty()) body.add(DialogBody.plainMessage(DialogText.muted("No candidates are available."), 320));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 320));
        dialogs.show(player, manage ? "Manage Candidates" : "Vote", body, List.of(), buttons, 2, 150, null);
    }
}
