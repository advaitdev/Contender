package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.hacker.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.BiConsumer;

public final class HackerDialogs {
    private static final List<HackSetting> COMBAT = List.of(HackSetting.REACH, HackSetting.ATTACK_SPEED, HackSetting.ATTACK_DAMAGE, HackSetting.RESISTANCE);
    private static final List<HackSetting> MOVEMENT = List.of(HackSetting.ANTI_KNOCKBACK, HackSetting.MOVEMENT_SPEED, HackSetting.JUMP_STRENGTH, HackSetting.STEP_HEIGHT);
    private static final class Draft {
        final UUID selection;
        HackSettings settings;
        Draft(HackerManager.Profile profile) { selection = profile.selection(); settings = profile.settings(); }
    }
    private final HackerManager hackers;
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    public HackerDialogs(Contender plugin, HackerManager hackers) { this.plugin = plugin; this.hackers = hackers; dialogs = new Dialogs(plugin); }
    public void open(Player player) {
        if (!hackers.isHacker(player.getUniqueId())) {
            player.closeDialog(); Dialogs.error(player, "Only selected hackers can open this menu."); return;
        }
        Draft draft = new Draft(hackers.profile(player.getUniqueId()));
        drafts.put(player.getUniqueId(), draft); edit(player, draft, false);
    }
    public void forget(Player player) { drafts.remove(player.getUniqueId()); }
    public void clear() {
        for (Player player : plugin.getServer().getOnlinePlayers()) if (drafts.containsKey(player.getUniqueId())) player.closeDialog();
        drafts.clear();
    }
    private void check(Player player, Draft draft) {
        var profile = hackers.profile(player.getUniqueId());
        if (drafts.get(player.getUniqueId()) != draft || !hackers.isHacker(player.getUniqueId()) || profile == null || !profile.selection().equals(draft.selection)) {
            throw new IllegalStateException("This menu has expired. Open /hacks again.");
        }
    }
    private ActionButton button(Player owner, Draft draft, DialogIcon icon, String label, boolean primary, BiConsumer<Player, DialogResponseView> callback) {
        return dialogs.button(owner, icon.label(label, primary ? DialogPalette.ACCENT : DialogPalette.MUTED), null, false, 150,
                (player, response) -> { check(player, draft); callback.accept(player, response); });
    }
    private void edit(Player player, Draft draft, boolean movement) {
        check(player, draft);
        var settings = movement ? MOVEMENT : COMBAT;
        var inputs = new ArrayList<DialogInput>();
        Component ranges = Component.empty();
        for (var setting : settings) {
            inputs.add(DialogInput.text(setting.key(), setting.icon.label(setting.label + " (" + setting.unit + ")"))
                    .initial(HackSetting.number(draft.settings.get(setting))).maxLength(12).width(300).build());
            if (!ranges.equals(Component.empty())) ranges = ranges.appendNewline();
            ranges = ranges.append(DialogPalette.text(setting.label + ": " + HackSetting.number(setting.min) + " to " + setting.display(setting.max), DialogPalette.MUTED));
        }
        var buttons = new ArrayList<ActionButton>();
        ActionButton back = button(player, draft, DialogIcon.BACK, movement ? "Back: Combat" : "Discard Changes", false, (p, response) -> {
            if (movement) { read(draft, settings, response); edit(p, draft, false); }
            else { forget(p); p.closeDialog(); }
        });
        ActionButton next = button(player, draft, DialogIcon.NEXT, movement ? "Next: Review" : "Next: Movement", true, (p, response) -> {
            read(draft, settings, response);
            if (movement) review(p, draft); else edit(p, draft, true);
        });
        Dialogs.navigationRow(buttons, back, next, 150);
        dialogs.show(player, movement ? "Hacks: Movement" : "Hacks: Combat", List.of(
                DialogBody.plainMessage(DialogPalette.text("Only active while you are fighting in a duel.\nChanges apply after you save on the Review screen.", DialogPalette.MUTED), 300),
                DialogBody.plainMessage(ranges, 300)), inputs, buttons, 2, 300, null);
    }
    private void read(Draft draft, List<HackSetting> settings, DialogResponseView response) {
        var changes = new EnumMap<HackSetting, Double>(HackSetting.class);
        for (var setting : settings) {
            try { changes.put(setting, setting.validate(Double.parseDouble(Dialogs.text(response, setting.key())))); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter a number for " + setting.label + "."); }
        }
        draft.settings = draft.settings.with(changes);
    }
    private void review(Player player, Draft draft) {
        check(player, draft);
        Component summary = Component.empty();
        for (var setting : HackSetting.values()) {
            summary = summary.append(setting.icon.sprite()).append(DialogPalette.text(" " + setting.label + ": ", DialogPalette.MUTED))
                    .append(DialogPalette.text(setting.display(draft.settings.get(setting)), DialogPalette.TEXT)).appendNewline();
        }
        List<HackSetting> warnings = draft.settings.warnings();
        Component warning = DialogPalette.text("Abilities apply while you are fighting.\nVoid damage and sumo fall losses still count.", DialogPalette.MUTED);
        if (!warnings.isEmpty()) {
            warning = DialogIcon.INFO.label("These Settings May Look Blatant", DialogPalette.WARNING)
                    .appendNewline().append(DialogPalette.text(String.join(", ", warnings.stream().map(setting -> setting.label).toList())
                            + ".\nOther players may notice these values. You can go back and lower them, or use them anyway.", DialogPalette.TEXT));
        }
        var buttons = new ArrayList<ActionButton>();
        Dialogs.navigationRow(buttons,
                button(player, draft, DialogIcon.BACK, "Back: Movement", false, (p, response) -> edit(p, draft, true)),
                button(player, draft, DialogIcon.SAVE, warnings.isEmpty() ? "Save & Close" : "Use These Settings", true, (p, response) -> {
                    hackers.update(p, draft.selection, draft.settings); forget(p); p.closeDialog();
                }), 150);
        dialogs.show(player, "Review Hacks", List.of(DialogBody.plainMessage(summary, 300), DialogBody.plainMessage(warning, 300)), List.of(), buttons, 2, 300, null);
    }
}
