package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        // One line keeps the buttons on screen at small GUI sizes; the title already names the game.
        Component summary = game.name().equalsIgnoreCase(game.type().name()) ? Component.empty()
                : text(game.type().name(), TEXT).append(text("  ·  ", MUTED));
        summary = summary.append(text(game.statusText(), game.state() == Minigame.State.RUNNING ? SUCCESS : game.cancelled() ? DANGER : TEXT))
                .append(text("  ·  " + online + " of " + entered + " online", MUTED));
        body.add(DialogBody.plainMessage(summary, 320));
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
        if (!game.finished()) buttons.add(button(player, DialogIcon.PLAYERS, "Manage Players", TEXT, "Change scores or withdraw players.", p -> players(p, game)));
        new TournamentDialogs(plugin).addShowButtons(player, buttons);
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
                    text("  " + status(participant.status), MUTED));
            buttons.add(dialogs.button(player, label, DialogText.muted("Change their score or withdraw them."), true, WIDE,
                    (p, view) -> playerPage(p, game, participant.id)));
        }
        dialogs.show(player, "Manage Players", List.of(DialogBody.plainMessage(DialogText.muted(buttons.isEmpty() ? "Nobody is left in the game." : "Click a player to change their score or withdraw them."), 320)),
                List.of(), buttons, 1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> control(p, game)));
    }

    private static String status(Minigame.Status status) {
        return switch (status) {
            case PLAYING -> "In play";
            case OUT -> "Out";
            case DONE -> "Done";
            case WAITING -> "Waiting";
            case WITHDRAWN -> "Withdrawn";
        };
    }

    /** The score this player can have corrected right now, or null. */
    private static Minigame.ScoreEdit scoreEdit(Minigame game, Minigame.Participant participant) {
        return game.state() == Minigame.State.RUNNING ? game.scoreEdit(participant) : null;
    }

    private void playerPage(Player player, Minigame game, UUID id) {
        Minigame.Participant participant = require(game).participant(id);
        if (participant == null || participant.status == Minigame.Status.WITHDRAWN) { players(player, game); return; }
        Minigame.ScoreEdit edit = scoreEdit(game, participant);
        Component info = DialogText.detail("Status", status(participant.status));
        if (edit != null) info = DialogText.lines(info, DialogText.detail(edit.label(), Integer.toString(edit.current()), ACCENT));
        List<ActionButton> buttons = new ArrayList<>();
        if (edit != null) buttons.add(button(player, DialogIcon.SETTINGS, "Change Score", TEXT, "Fix it if something went wrong.", p -> scoreForm(p, game, id)));
        buttons.add(button(player, DialogIcon.CLOSE, "Withdraw", DANGER, "They go back to the lobby.", p -> confirmWithdraw(p, game, id)));
        dialogs.show(player, participant.name, List.of(DialogBody.plainMessage(info, 320)), List.of(), buttons, 1, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> players(p, game)));
    }

    private void scoreForm(Player player, Minigame game, UUID id) {
        Minigame.Participant participant = require(game).participant(id);
        Minigame.ScoreEdit edit = participant == null ? null : scoreEdit(game, participant);
        if (edit == null) { playerPage(player, game, id); return; }
        List<DialogInput> inputs = List.of(DialogInput.text("score", DialogIcon.SETTINGS.label(edit.label()))
                .initial(Integer.toString(edit.current())).maxLength(6).width(WIDE).build());
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> playerPage(p, game, id)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), null, true, NAV, (p, view) -> {
                    int value;
                    try { value = Integer.parseInt(java.util.Objects.requireNonNullElse(view.getText("score"), "").strip()); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter a whole number from " + edit.min() + " to " + edit.max() + "."); }
                    require(game).correctScore(id, value);
                    Dialogs.tell(p, participant.name + ": " + edit.label() + " set to " + value + ".");
                    playerPage(p, game, id);
                }), NAV);
        dialogs.show(player, "Change Score", List.of(DialogBody.plainMessage(DialogText.lines(text(participant.name, TEXT),
                DialogText.muted("Enter a whole number from " + edit.min() + " to " + edit.max() + ".")), 320)), inputs, buttons, 2, NAV, null);
    }

    private void confirmWithdraw(Player player, Minigame game, UUID id) {
        Minigame.Participant participant = require(game).participant(id);
        if (participant == null) { players(player, game); return; }
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> playerPage(p, game, id)),
                dialogs.button(player, DialogIcon.CLOSE.label("Withdraw", DANGER), null, true, NAV, (p, view) -> {
                    require(game).withdraw(id);
                    players(p, game);
                }), NAV);
        dialogs.show(player, "Withdraw " + participant.name + "?", List.of(DialogBody.plainMessage(
                DialogText.muted("They go back to the lobby and leave this game."), 320)), List.of(), buttons, 2, NAV, null);
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
