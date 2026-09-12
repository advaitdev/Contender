package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.role.RoleStyle;
import me.advait.contender.tab.TabStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.Consumer;

public final class SettingsDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public SettingsDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        if (player.hasPermission("contender.master")) {
            buttons.add(option(player, DialogIcon.BOARD, "Tab Title & Bracket", true, this::tab));
            buttons.add(option(player, DialogIcon.BOARD, "Board Appearance", true, this::board));
            for (PlayerRole role : List.of(PlayerRole.DIRECTOR, PlayerRole.SPECTATOR)) {
                buttons.add(option(player, DialogIcon.NAME, role.label() + " Nametags", true, p -> style(p, role)));
            }
            buttons.add(option(player, DialogIcon.PLAYERS, "Player Roles", true, p -> new RoleDialogs(plugin).open(p)));
        }
        if (player.hasPermission("contender.admin")) {
            buttons.add(option(player, DialogIcon.SPAWN, "Lobby Settings", false, p -> new GameSettingsDialogs(plugin).lobby(p)));
            buttons.add(option(player, DialogIcon.CHAT, "Chat & Voice", false, p -> new GameSettingsDialogs(plugin).chat(p)));
            buttons.add(option(player, DialogIcon.DUEL, "PvP Settings", false, p -> new GameSettingsDialogs(plugin).pvp(p)));
        }
        dialogs.show(player, "Options", List.of(DialogBody.plainMessage(DialogText.detail("Your role",
                plugin.getRoleManager().getRole(player.getUniqueId()).label()), 320)), List.of(), buttons, 1, 150, null);
    }
    private ActionButton option(Player player, DialogIcon icon, String name, boolean master, Consumer<Player> callback) {
        return dialogs.button(player, icon.label(name), null, master, 300, (p, view) -> callback.accept(p));
    }
    private static DialogInput colors(String label, NamedTextColor selected) {
        return DialogInput.singleOption("color", Component.text(label), NamedTextColor.NAMES.keys().stream()
                .map(name -> Dialogs.option(name, name.replace('_', ' '), NamedTextColor.NAMES.value(name).equals(selected))).toList()).build();
    }
    private void style(Player player, PlayerRole role) { style(player, role, RoleStyle.read(plugin.getConfig(), role)); }
    private void style(Player player, PlayerRole role, RoleStyle draft) {
        List<DialogInput> inputs = List.of(Dialogs.text("prefix", "Prefix", draft.prefix(), 32), colors("Prefix and name color", draft.color()));
        List<DialogBody> body = List.of(DialogBody.plainMessage(DialogText.muted(
                "Applies to everyone with this role.\nLeave the prefix empty to show only their name.\n\nShow preview to see your edits. Save applies them."), 440),
                DialogBody.plainMessage(DialogText.heading("Nametag preview")),
                DialogBody.plainMessage(draft.displayName(player.getName())));
        dialogs.show(player, role.label() + " nametags", body, inputs,
                List.of(dialogs.button(player, DialogIcon.PREVIEW, "Show preview", (p, view) -> style(p, role, roleDraft(view))),
                        dialogs.button(player, DialogIcon.SAVE, "Save", (p, view) -> {
                            roleDraft(view).write(plugin.getConfig(), role);
                            plugin.saveConfig();
                            plugin.getNameTagManager().refresh();
                            plugin.getTabManager().refresh();
                            Dialogs.tell(p, role.label() + " nametags updated.");
                            open(p);
                        }), dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> open(p))));
    }
    static RoleStyle roleDraft(DialogResponseView view) {
        return new RoleStyle(Dialogs.text(view, "prefix"), RoleStyle.parseColor(Dialogs.text(view, "color")));
    }
    public void tab(Player player) { tab(player, this::open); }
    public void tab(Player player, Consumer<Player> back) { tab(player, TabStyle.read(plugin.getConfig()), back); }
    private void tab(Player player, TabStyle draft, Consumer<Player> back) {
        var tournament = plugin.getTournamentManager().current();
        List<DialogInput> inputs = List.of(DialogInput.text("title", DialogIcon.NAME.label("Tournament Title"))
                        .initial(draft.title()).maxLength(64).width(300).build(),
                DialogInput.singleOption("color", DialogIcon.PREVIEW.label("Title Color"), NamedTextColor.NAMES.keys().stream()
                        .map(name -> Dialogs.option(name, name.replace('_', ' '), NamedTextColor.NAMES.value(name).equals(draft.color()))).toList()).width(300).build(),
                DialogInput.singleOption("enabled", DialogIcon.BOARD.label("Tab Bracket"),
                        List.of(Dialogs.option("true", "On", draft.enabled()), Dialogs.option("false", "Off", !draft.enabled()))).width(300).build());
        List<DialogBody> body = List.of(DialogBody.plainMessage(DialogText.muted(
                "Leave the title blank to use the tournament name.\nPreview checks your edits. Save applies them for everyone."), 320),
                DialogBody.plainMessage(DialogText.lines(DialogIcon.PREVIEW.label("Title Preview", DialogPalette.ACCENT),
                        draft.header(tournament == null ? null : tournament.name())), 320));
        dialogs.show(player, "Tab Title", body, inputs,
                List.of(dialogs.button(player, DialogIcon.PREVIEW.label("Show Preview"), null, true, 150,
                                (p, view) -> tab(p, tabDraft(view), back)),
                        dialogs.button(player, DialogIcon.SAVE.label("Save", DialogPalette.ACCENT), null, true, 150, (p, view) -> {
                            tabDraft(view).write(plugin.getConfig()); plugin.saveConfig(); plugin.getTabManager().refresh();
                            Dialogs.tell(p, "Tab settings updated."); back.accept(p);
                        })), 2, 150, dialogs.button(player, DialogIcon.BACK.label("Back", DialogPalette.MUTED), null, true, 150,
                        (p, view) -> back.accept(p)));
    }
    public void board(Player player) { board(player, this::open); }
    public void board(Player player, Consumer<Player> back) {
        dialogs.show(player, "Board Appearance", List.of(DialogBody.plainMessage(
                DialogText.muted("0% removes the background.\n100% makes it solid. The text keeps its shadow."), 320)),
                List.of(DialogInput.numberRange("opacity", DialogIcon.BOARD.label("Background Opacity (%)"), 0, 100)
                        .initial((float) me.advait.contender.tournament.TournamentBoard.backgroundOpacity(plugin.getConfig()))
                        .step(5f).width(300).build()),
                List.of(dialogs.button(player, DialogIcon.BACK.label("Back", DialogPalette.MUTED), null, true, 150,
                                (p, view) -> back.accept(p)),
                        dialogs.button(player, DialogIcon.SAVE.label("Save", DialogPalette.ACCENT), null, true, 150, (p, view) -> {
                    plugin.getConfig().set("tournament-board.background-opacity", Dialogs.number(view, "opacity", 0, 100));
                    plugin.saveConfig();
                    var manager = plugin.getTournamentManager(); manager.board().update(manager.current(), manager.playing());
                    Dialogs.tell(p, "Board background updated."); back.accept(p);
                })), 2, 150, null);
    }
    static TabStyle tabDraft(DialogResponseView view) {
        return new TabStyle(Dialogs.text(view, "title"), RoleStyle.parseColor(Dialogs.text(view, "color")), Dialogs.text(view, "enabled").equals("true"));
    }
}
