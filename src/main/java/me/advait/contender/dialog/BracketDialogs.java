package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.tab.BracketLayout;
import me.advait.contender.tab.TabManager;
import me.advait.contender.tab.TabStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static me.advait.contender.dialog.DialogPalette.*;

/** Public event views never expose setup, roster, or scheduling controls. */
public final class BracketDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public BracketDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }

    private ActionButton button(Player player, DialogIcon icon, String label, TextColor color,
                                BiConsumer<Player, DialogResponseView> action) {
        return dialogs.button(player, icon.label(label, color), null, false, 150, action);
    }

    public void open(Player player) {
        var manager = plugin.getTabManager();
        var event = manager.event();
        if (event == null || event.cancelled()) {
            dialogs.show(player, "Leaderboard", DialogText.muted("No tournament is selected."), List.of(), List.of());
            return;
        }
        if (event.minigame()) { preview(player, event.id(), 0); return; }
        var tournament = plugin.getTournamentManager().current();
        var view = manager.view(player);
        var layout = manager.layout(player);
        if (tournament == null || layout == null) return;
        var options = new ArrayList<SingleOptionDialogInput.OptionEntry>();
        options.add(Dialogs.option("0", "Follow Current Round", view.round() == 0));
        for (int i = 1; i <= BracketLayout.rounds(tournament); i++) options.add(Dialogs.option(Integer.toString(i), "Round " + i, view.round() == i));
        List<DialogInput> inputs = List.of(DialogInput.singleOption("round", DialogIcon.TOURNAMENT.label("Round", TEXT), options).width(300).build());
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(player, DialogIcon.TOURNAMENT, "Show Round", ACCENT, (p, response) -> {
            sameEvent(event.id());
            manager.setView(p, new BracketLayout.View(Integer.parseInt(Dialogs.text(response, "round")), 0, false));
            p.closeDialog();
        }));
        buttons.add(button(player, DialogIcon.BOARD, "Show Standings", TEXT, (p, response) -> {
            sameEvent(event.id()); manager.setView(p, new BracketLayout.View(0, 0, true)); p.closeDialog();
        }));
        buttons.add(button(player, DialogIcon.PREVIEW, "Show Preview", TEXT, (p, response) -> preview(p, event.id(), 0)));
        if (player.hasPermission("contender.master")) buttons.add(dialogs.button(player, DialogIcon.SETTINGS.label("Tab Settings", TEXT), null, true, 150,
                (p, response) -> new SettingsDialogs(plugin).tab(p)));
        else buttons.add(button(player, DialogIcon.REFRESH, "Refresh", TEXT, (p, response) -> open(p)));
        if (layout.pages() > 1) {
            Dialogs.navigationRow(buttons, layout.page() > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, (p, response) -> {
                sameEvent(event.id()); manager.setView(p, new BracketLayout.View(view.round(), layout.page() - 1, view.standings())); open(p);
            }) : null, layout.page() + 1 < layout.pages() ? button(player, DialogIcon.NEXT, "Next", ACCENT, (p, response) -> {
                sameEvent(event.id()); manager.setView(p, new BracketLayout.View(view.round(), layout.page() + 1, view.standings())); open(p);
            }) : null, 150);
        }
        Component body = DialogText.paragraphs(DialogText.heading(layout.caption()),
                DialogText.muted("Choose a view, then hold Tab.\nThis only changes your view."),
                DialogText.lines(DialogText.detail("Offline", "X if any teammate is offline"),
                        DialogText.detail("Points", "Win 3 · Draw 1"), DialogText.muted("Ties use round difference.")));
        if (!manager.supportsBracket()) body = DialogText.paragraphs(body,
                DialogText.muted("The tab bracket is unavailable on this server.\nYou can still open the preview."));
        dialogs.show(player, "Tab Bracket", List.of(DialogBody.plainMessage(body, 320)), inputs, buttons, 2, 150, null);
    }

    private TabManager.Event sameEvent(UUID id) {
        var current = plugin.getTabManager().event();
        if (current == null || current.cancelled() || !current.id().equals(id))
            throw new IllegalStateException("The tournament has changed. Open /bracket again.");
        return current;
    }

    private void preview(Player player, UUID eventId, int column) {
        var event = sameEvent(eventId);
        var layout = plugin.getTabManager().layout(player);
        if (layout == null) { open(player); return; }
        int columns = Math.max(1, (layout.rows().size() + 19) / 20);
        int selected = Math.clamp(column, 0, columns - 1);
        Component body = TabStyle.read(plugin.getConfig()).header(event.name())
                .appendNewline().append(DialogText.muted(event.minigame() ? event.caption() + " | " + event.status() : layout.caption())).appendNewline();
        for (int i = selected * 20; i < Math.min(layout.rows().size(), (selected + 1) * 20); i++) {
            var row = layout.rows().get(i);
            body = body.appendNewline().append(row.boardText());
            if (row.latency() < 0) body = body.append(Component.text(" X", NamedTextColor.GRAY));
        }
        var buttons = new ArrayList<ActionButton>();
        if (!event.minigame()) {
            buttons.add(button(player, DialogIcon.BACK, "Back", MUTED, (p, response) -> open(p)));
            buttons.add(button(player, DialogIcon.REFRESH, "Refresh", TEXT, (p, response) -> preview(p, eventId, selected)));
        } else if (columns == 1) {
            dialogs.show(player, "Leaderboard", List.of(DialogBody.plainMessage(body, 450)), List.of(),
                    List.of(dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, 300,
                            (p, response) -> preview(p, eventId, selected))), 1, 150, null);
            return;
        }
        if (columns > 1) Dialogs.navigationRow(buttons,
                selected > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, (p, response) -> preview(p, eventId, selected - 1)) : null,
                selected + 1 < columns ? button(player, DialogIcon.NEXT, "Next", ACCENT, (p, response) -> preview(p, eventId, selected + 1)) : null, 150);
        dialogs.show(player, event.minigame() ? "Leaderboard" : "Bracket Preview", List.of(DialogBody.plainMessage(body, 450)), List.of(), buttons, 2, 150, null);
    }
}
