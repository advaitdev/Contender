package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static me.advait.contender.dialog.DialogPalette.*;

/** The hacker's sabotage menu and the director's sabotage settings. */
public final class SabotageDialogs {
    private static final int CELL = 170, NAV = 150, WIDE = 300;
    private final Contender plugin;
    private final Dialogs dialogs;

    public SabotageDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    // ---- Hacker --------------------------------------------------------------------------------

    public void open(Player player) {
        SabotageService service = plugin.getSabotage();
        if (!service.available(player)) { player.closeDialog(); Dialogs.error(player, "Sabotages aren't available right now."); return; }
        String general = service.blocked(player, null);
        List<DialogBody> body = new ArrayList<>();
        SabotageService.Scope scope = service.scopeFor(player);
        String where = scope != null && scope.duel() != null ? "this duel" : "this event";
        Component status = DialogText.lines(DialogText.muted("Change the rules for everyone in " + where + ". " + (service.revealHacker() ? "Everyone will see it was you." : "Only directors see it was you.")),
                DialogText.detail("Sabotages left", Integer.toString(service.usesLeft(player)), ACCENT));
        if (general != null) status = DialogText.lines(status, text(general, WARNING));
        if (!service.active().isEmpty()) status = DialogText.lines(status, DialogText.detail("Running", String.join(", ",
                service.active().values().stream().map(active -> active.sabotage().name()).toList())));
        body.add(DialogBody.plainMessage(status, 340));
        List<ActionButton> buttons = new ArrayList<>();
        for (Sabotage sabotage : service.all()) {
            if (!service.allowed(sabotage.id())) continue;
            String reason = service.blocked(player, sabotage);
            Component label = sabotage.icon().label(sabotage.name(), reason == null ? TEXT : MUTED);
            Component tooltip = reason == null ? DialogText.muted(sabotage.description()) : DialogText.lines(DialogText.muted(sabotage.description()), text(reason, WARNING));
            buttons.add(dialogs.button(player, label, tooltip, false, CELL, (p, view) -> confirm(p, sabotage)));
        }
        if (buttons.isEmpty()) body.add(DialogBody.plainMessage(DialogText.muted("The director hasn't allowed any sabotages."), 340));
        ActionButton exit = plugin.getHackers().isHacker(player.getUniqueId())
                ? dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, false, NAV, (p, view) -> new HackerDialogs(plugin).open(p))
                : null;
        dialogs.show(player, "Sabotage", body, List.of(), buttons, 2, NAV, exit);
    }

    private void confirm(Player player, Sabotage sabotage) {
        String reason = plugin.getSabotage().blocked(player, sabotage);
        if (reason != null) throw new IllegalStateException(reason);
        SabotageService service = plugin.getSabotage();
        String duration = service.durationSeconds() == 0 ? "until this event ends" : "for " + service.durationSeconds() + " seconds";
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(sabotage.icon().label(sabotage.name(), ACCENT), 300),
                DialogBody.plainMessage(text(sabotage.description(), TEXT), 300),
                DialogBody.plainMessage(DialogText.muted("Lasts " + duration + (service.affectsHackers() ? ", and affects you too." : ". You are not affected.")), 300));
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, false, NAV, (p, view) -> open(p)),
                dialogs.button(player, DialogIcon.SKULL.label("Sabotage", ACCENT), null, false, NAV, (p, view) -> {
                    plugin.getSabotage().trigger(p, sabotage);
                    p.closeDialog();
                }), NAV);
        dialogs.show(player, "Sabotage?", body, List.of(), buttons, 2, NAV, null);
    }

    // ---- Director ------------------------------------------------------------------------------

    public void settings(Player player, Consumer<Player> back) {
        SabotageService service = plugin.getSabotage();
        List<DialogInput> inputs = List.of(
                toggle("enabled", DialogIcon.SKULL, "Hackers Can Sabotage", service.enabled(), "Yes", "No, only directors"),
                toggle("affects_hackers", DialogIcon.PLAYERS, "Affects the Hackers Too", service.affectsHackers(), "Yes", "No"),
                toggle("reveal", DialogIcon.EYE, "Show Who Sabotaged", service.revealHacker(), "Everyone sees", "Only directors see"),
                durationInput(service.durationSeconds()),
                DialogInput.numberRange("uses", DialogIcon.STAR.label("Sabotages per Hacker per Event"), 0, 20)
                        .initial((float) service.usesPerHacker()).step(1f).width(WIDE).build(),
                DialogInput.numberRange("cooldown", DialogIcon.REFRESH.label("Wait Between Sabotages"), 0, 600)
                        .initial((float) service.cooldownSeconds()).step(10f).width(WIDE).labelFormat("%s: %ss").build(),
                DialogInput.numberRange("max_active", DialogIcon.SETTINGS.label("Sabotages at Once"), 1, 10)
                        .initial((float) service.maxActive()).step(1f).width(WIDE).build());
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogs.button(player, DialogIcon.SETTINGS.label("Choose Sabotages", TEXT), DialogText.muted("Pick which sabotages hackers can use."), true, NAV,
                (p, view) -> { save(view); choose(p, back); }));
        buttons.add(dialogs.button(player, DialogIcon.SKULL.label("Start One Now", TEXT), DialogText.muted("Trigger a sabotage yourself."), true, NAV,
                (p, view) -> { save(view); force(p, back); }));
        if (!service.active().isEmpty()) buttons.add(dialogs.button(player, DialogIcon.CLOSE.label("End All", DANGER), DialogText.muted("Stop every running sabotage."), true, NAV,
                (p, view) -> { save(view); plugin.getSabotage().endAll(true); settings(p, back); }));
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> back.accept(p)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), null, true, NAV, (p, view) -> { save(view); Dialogs.tell(p, "Sabotage settings saved."); back.accept(p); }), NAV);
        Component running = service.active().isEmpty() ? DialogText.muted("None running.")
                : DialogText.detail("Running", String.join(", ", service.active().values().stream().map(a -> a.sabotage().name()).toList()));
        dialogs.show(player, "Sabotage Settings", List.of(DialogBody.plainMessage(DialogText.lines(
                DialogText.muted(service.enabled() ? "Hackers use /sabotage during an event. You can also use Start One Now."
                        : "Only directors start sabotages, with Start One Now."), running), 320)), inputs, buttons, 2, NAV, null);
    }

    private void save(DialogResponseView view) {
        int duration;
        try { duration = Integer.parseInt(Dialogs.text(view, "duration")); }
        catch (NumberFormatException invalid) { duration = plugin.getSabotage().durationSeconds(); }
        plugin.getSabotage().configure(bool(view, "enabled"), bool(view, "affects_hackers"), duration,
                Dialogs.number(view, "uses", 0, 20), Dialogs.number(view, "cooldown", 0, 600), Dialogs.number(view, "max_active", 1, 10), bool(view, "reveal"));
    }

    private static final int[] LENGTHS = {0, 30, 60, 120, 180, 300, 600, 900, 1800};

    private static DialogInput durationInput(int current) {
        List<Integer> lengths = new ArrayList<>(Arrays.stream(LENGTHS).boxed().toList());
        if (!lengths.contains(current)) { lengths.add(current); lengths.sort(Integer::compare); }
        return DialogInput.singleOption("duration", DialogIcon.CLOCK.label("Each Sabotage Lasts"), lengths.stream()
                .map(seconds -> Dialogs.option(Integer.toString(seconds), seconds == 0 ? "The whole event" : length(seconds), seconds == current)).toList()).width(WIDE).build();
    }

    private static String length(int seconds) {
        if (seconds % 60 != 0) return seconds + " seconds";
        int minutes = seconds / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private void choose(Player player, Consumer<Player> back) {
        SabotageService service = plugin.getSabotage();
        List<ActionButton> buttons = new ArrayList<>();
        for (Sabotage sabotage : service.all()) {
            boolean on = service.allowed(sabotage.id());
            Component label = sabotage.icon().label(sabotage.name(), on ? TEXT : MUTED);
            if (on) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            buttons.add(dialogs.button(player, label, DialogText.muted(sabotage.description() + (on ? "\nClick to turn off." : "\nClick to turn on.")), true, CELL,
                    (p, view) -> { plugin.getSabotage().setAllowed(sabotage.id(), !plugin.getSabotage().allowed(sabotage.id())); choose(p, back); }));
        }
        dialogs.show(player, "Choose Sabotages", List.of(DialogBody.plainMessage(DialogText.muted("Checked sabotages appear in the hackers' menu."), 340)),
                List.of(), buttons, 2, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> settings(p, back)));
    }

    private void force(Player player, Consumer<Player> back) {
        SabotageService service = plugin.getSabotage();
        List<ActionButton> buttons = new ArrayList<>();
        for (Sabotage sabotage : service.all()) {
            boolean running = service.active().containsKey(sabotage.id());
            Component label = sabotage.icon().label(sabotage.name(), running ? ACCENT : TEXT);
            buttons.add(dialogs.button(player, label, DialogText.muted(running ? "Running. Click to end it." : sabotage.description()), true, CELL, (p, view) -> {
                if (plugin.getSabotage().active().containsKey(sabotage.id())) plugin.getSabotage().end(sabotage.id(), true);
                else plugin.getSabotage().force(sabotage, p.getUniqueId());
                force(p, back);
            }));
        }
        dialogs.show(player, "Start a Sabotage", List.of(DialogBody.plainMessage(DialogText.muted("Starts right away for everyone in the event. Click a running one to end it."), 340)),
                List.of(), buttons, 2, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> settings(p, back)));
    }

    private static DialogInput toggle(String key, DialogIcon icon, String label, boolean value, String yes, String no) {
        return DialogInput.singleOption(key, icon.label(label), List.of(Dialogs.option("true", yes, value), Dialogs.option("false", no, !value))).width(WIDE).build();
    }

    private static boolean bool(DialogResponseView view, String key) { return Dialogs.text(view, key).equals("true"); }
}
