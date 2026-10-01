package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelTeam;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.spectate.Spectatable;
import me.advait.contender.tab.TabText;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static me.advait.contender.dialog.DialogPalette.*;

/** /spectate: every live match and minigame, one button each. */
public final class SpectateDialogs {
    private static final int WIDTH = 300, NAV = 150, PER_PAGE = 8;
    private final Contender plugin;
    private final Dialogs dialogs;

    public SpectateDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    public void open(Player player) { open(player, 0); }

    private void open(Player player, int requestedPage) {
        List<Spectatable> targets = new ArrayList<>();
        Minigame game = plugin.getMinigames().current();
        if (game != null && game.acceptsWatchers()) targets.add(game);
        for (Duel duel : plugin.getDuels().duels()) if (duel.acceptsWatchers()) targets.add(duel);
        int pages = Math.max(1, (targets.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        Spectatable watching = plugin.getSpectate().target(player.getUniqueId());
        for (Spectatable target : targets.subList(page * PER_PAGE, Math.min(targets.size(), (page + 1) * PER_PAGE))) {
            Component label = target instanceof Duel duel ? duelLabel(duel) : DialogIcon.MACE.label(target.displayName(), TEXT);
            if (target == watching) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            Component tooltip = target instanceof Duel duel ? duelTooltip(duel) : DialogText.muted("Watch " + target.displayName() + ".");
            buttons.add(dialogs.button(player, label, tooltip, false, WIDTH, (p, view) -> {
                plugin.getSpectate().watch(p, target);
                p.closeDialog();
            }));
        }
        List<DialogBody> body = new ArrayList<>();
        if (targets.isEmpty()) body.add(DialogBody.plainMessage(DialogText.muted("Nothing is being played right now."), 320));
        else body.add(DialogBody.plainMessage(DialogText.muted("Choose a match to watch. You'll be in Spectator mode."), 320));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 320));
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, false, NAV, (p, view) -> open(p, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, false, NAV, (p, view) -> open(p, page + 1)) : null, NAV);
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.SPAWN.label("Lobby", TEXT), DialogText.muted("Stop watching and go back."), false, NAV, (p, view) -> {
                    p.closeDialog();
                    me.advait.contender.command.Commands.lobby(plugin, p);
                }),
                dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, NAV, (p, view) -> open(p, page)), NAV);
        dialogs.show(player, "Spectate", body, List.of(), buttons, 2, NAV, null);
    }

    private Component duelLabel(Duel duel) {
        List<DuelTeam> teams = duel.teams();
        Component label = Component.empty();
        for (int i = 0; i < Math.min(2, teams.size()); i++) {
            if (i > 0) label = label.append(text(" vs ", MUTED));
            label = label.append(side(teams.get(i)));
        }
        if (teams.size() > 2) label = label.append(text(" +" + (teams.size() - 2), MUTED));
        StringBuilder score = new StringBuilder();
        for (int i = 0; i < teams.size(); i++) score.append(i == 0 ? "" : "-").append(teams.get(i).score());
        return label.append(text("  " + score, ACCENT));
    }

    private Component side(DuelTeam team) {
        String name = team.name();
        while (TabText.width(name) > 96 && name.length() > 2) name = name.substring(0, name.length() - 2) + "…";
        if (team.players().isEmpty()) return text(name, TEXT);
        var id = team.players().getFirst();
        var offline = Bukkit.getOfflinePlayer(id);
        return Component.textOfChildren(StringUtil.resolvedHead(id, offline.getName(), offline.getPlayerProfile().getProperties()),
                Component.space(), text(name, TEXT));
    }

    private Component duelTooltip(Duel duel) {
        Component tooltip = Component.empty();
        boolean first = true;
        for (DuelTeam team : duel.teams()) {
            if (!first) tooltip = tooltip.append(Component.newline());
            first = false;
            tooltip = tooltip.append(text(team.name() + "  " + team.score(), ACCENT));
            for (var id : team.players()) {
                String name = Bukkit.getOfflinePlayer(id).getName();
                tooltip = tooltip.append(Component.newline()).append(plugin.getNameTagManager().displayName(id, name == null ? "Player" : name));
            }
        }
        return tooltip.append(Component.newline()).append(DialogText.muted(duel.layout().getDisplayName() + " · round " + duel.round()));
    }
}
