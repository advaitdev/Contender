package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import me.advait.contender.Contender;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static me.advait.contender.dialog.DialogPalette.*;

/** The control panel for whichever minigame is selected. Every game shares it. */
public final class MinigameDialogs {
    private static final int WIDE = 300, NAV = 150;
    private final Contender plugin;
    private final Dialogs dialogs;

    public MinigameDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    private Minigame require(Minigame game) {
        if (plugin.getMinigames().current() != game) throw new IllegalStateException("That game is no longer selected.");
        return game;
    }

    public void control(Player player, Minigame game) {
        List<DialogBody> body = new ArrayList<>();
        long entered = game.roster().stream().filter(p -> p.status != Minigame.Status.WITHDRAWN).count();
        long online = game.roster().stream().filter(p -> p.status != Minigame.Status.WITHDRAWN && Bukkit.getPlayer(p.id) != null).count();
        body.add(DialogBody.plainMessage(DialogText.lines(
                DialogText.detail("Game", game.type().name()),
                DialogText.detail("Status", game.statusText(), game.state() == Minigame.State.RUNNING ? SUCCESS : game.cancelled() ? DANGER : TEXT),
                DialogText.detail("Players", online + " of " + entered + " online")), 320));
        List<ActionButton> buttons = new ArrayList<>();
        switch (game.state()) {
            case READY -> buttons.add(button(player, DialogIcon.NEXT, "Start " + game.type().name(), ACCENT, "Moves everyone in and starts the countdown.",
                    p -> { require(game).start(); p.closeDialog(); Dialogs.tell(p, "Starting " + game.name() + "."); }));
            case PREPARING, COUNTDOWN -> body.add(DialogBody.plainMessage(DialogText.muted("Getting ready…"), 320));
            case RUNNING -> buttons.add(button(player, DialogIcon.SAVE, "End Now", ACCENT, "Finish now and keep the current results.",
                    p -> { require(game).finish(); control(p, game); }));
            default -> buttons.add(button(player, DialogIcon.TOURNAMENT, "New Event", ACCENT, "Set up what comes next.",
                    p -> new TournamentDialogs(plugin).formats(p)));
        }
        if (game.acceptsWatchers()) buttons.add(button(player, DialogIcon.PREVIEW, "Spectate", TEXT, "Watch the game.",
                p -> { plugin.getSpectate().watch(p, require(game)); p.closeDialog(); }));
        buttons.add(button(player, DialogIcon.BOARD, "Leaderboard", TEXT, "See the current standings.", p -> new BracketDialogs(plugin).open(p)));
        if (!game.finished()) buttons.add(button(player, DialogIcon.PLAYERS, "Manage Players", TEXT, "Withdraw someone from this game.", p -> players(p, game)));
        buttons.add(button(player, DialogIcon.SETTINGS, "Setup Tools", TEXT, "Maps, kits, courses and displays.", p -> new TournamentDialogs(plugin).tools(p)));
        buttons.add(button(player, DialogIcon.SKULL, "Hacker Controls", TEXT, "Hackers, their hacks and sabotages.", p -> new HackerAdminDialogs(plugin).open(p)));
        if (!game.finished()) buttons.add(button(player, DialogIcon.CLOSE, "Cancel Game", DANGER, "Stop now. Everyone goes back to the lobby.", p -> confirmCancel(p, game)));
        dialogs.show(player, game.name(), body, List.of(), buttons, 1, NAV, null);
    }

    private ActionButton button(Player player, DialogIcon icon, String label, net.kyori.adventure.text.format.TextColor color, String hint,
                                java.util.function.Consumer<Player> action) {
        return dialogs.button(player, icon.label(label, color), DialogText.muted(hint), true, WIDE, (p, view) -> action.accept(p));
    }

    private void players(Player player, Minigame game) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Minigame.Participant participant : game.roster()) {
            if (participant.status == Minigame.Status.WITHDRAWN) continue;
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(participant.id), text(" " + participant.name, TEXT),
                    text("  " + participant.status.name().toLowerCase(java.util.Locale.ROOT), MUTED));
            buttons.add(dialogs.button(player, label, DialogText.muted("Withdraw " + participant.name + ". They go back to the lobby."), true, WIDE,
                    (p, view) -> { require(game).withdraw(participant.id); players(p, game); }));
        }
        dialogs.show(player, "Manage Players", List.of(DialogBody.plainMessage(DialogText.muted(buttons.isEmpty() ? "Nobody is left in the game." : "Click a player to withdraw them."), 320)),
                List.of(), buttons, 1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> control(p, game)));
    }

    private void confirmCancel(Player player, Minigame game) {
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> control(p, game)),
                dialogs.button(player, DialogIcon.CLOSE.label("Cancel Game", DANGER), null, true, NAV, (p, view) -> {
                    require(game).cancel("A director cancelled " + game.name() + ".");
                    control(p, game);
                }), NAV);
        dialogs.show(player, "Cancel " + game.name() + "?", List.of(DialogBody.plainMessage(DialogText.muted("Everyone returns to the lobby with their own items. Results so far are kept."), 320)),
                List.of(), buttons, 2, NAV, null);
    }
}
