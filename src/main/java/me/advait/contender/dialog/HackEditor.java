package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.core.Sounds;
import me.advait.contender.hacker.HackSetting;
import me.advait.contender.hacker.HackSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static me.advait.contender.dialog.DialogPalette.*;

/**
 * The hack grid shared by the hacker's own menu and the director's plan editor.
 * Each hack is one button showing its current strength; clicking it opens a short list of presets.
 * Changes are saved the moment a preset is chosen.
 */
final class HackEditor {
    private static final int CELL = 170, NAV = 150;
    private final Contender plugin;
    private final Dialogs dialogs;
    private final boolean admin;

    /**
     * @param title   dialog title
     * @param header  lines above the grid
     * @param current reads the latest settings (they can change while the menu is open)
     * @param save    stores new settings; throws if the player may no longer change them
     * @param back    where Back or Close goes; null shows a plain Close button
     */
    record Target(String title, List<Component> header, Function<Player, HackSettings> current,
                  BiConsumer<Player, HackSettings> save, Consumer<Player> back, List<Extra> extras) {
        Target(String title, List<Component> header, Function<Player, HackSettings> current,
               BiConsumer<Player, HackSettings> save, Consumer<Player> back) {
            this(title, header, current, save, back, List.of());
        }
    }

    /** An extra button under the grid, such as Sabotage. */
    record Extra(DialogIcon icon, String label, String hint, Consumer<Player> action) { }

    HackEditor(Contender plugin, boolean admin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
        this.admin = admin;
    }

    void grid(Player player, Target target) {
        HackSettings settings = target.current().apply(player);
        // One text block: separate blocks each add spacing, which pushes the last row off small screens.
        List<Component> lines = new ArrayList<>(target.header());
        lines.add(status(settings));
        List<DialogBody> body = List.of(DialogBody.plainMessage(DialogText.lines(lines.toArray(Component[]::new)), 340));
        List<ActionButton> buttons = new ArrayList<>();
        for (HackSetting setting : HackSetting.values()) {
            double value = settings.get(setting);
            Component label = setting.icon.label(setting.label + "  ", TEXT).append(text(setting.display(value), valueColor(setting, value)));
            Component tooltip = DialogText.lines(text(setting.description, TEXT),
                    DialogText.muted("Normal: " + normal(setting)),
                    DialogText.muted("Looks suspicious above " + HackSetting.number(setting.warning) + unitSuffix(setting)));
            buttons.add(dialogs.button(player, label, tooltip, admin, CELL, (p, view) -> levels(p, target, setting)));
        }
        buttons.add(dialogs.button(player, DialogIcon.CLOSE.label("All Off", TEXT), DialogText.muted("Turn every hack off."), admin, CELL,
                (p, view) -> apply(p, target, HackSettings.defaults(), "All hacks off.")));
        buttons.add(dialogs.button(player, DialogIcon.EYE.label("Closet Set", TEXT), DialogText.muted("Small boosts to reach, knockback, attack speed and resistance. Hard to spot."), admin, CELL,
                (p, view) -> apply(p, target, HackSettings.closet(), "Closet set applied.")));
        for (Extra extra : target.extras()) {
            buttons.add(dialogs.button(player, extra.icon().label(extra.label(), TEXT), DialogText.muted(extra.hint()), admin, CELL * 2 + 4,
                    (p, view) -> extra.action().accept(p)));
        }
        ActionButton exit = target.back() == null
                ? dialogs.button(player, text("Close", MUTED), null, admin, NAV, (p, view) -> p.closeDialog())
                : dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, admin, NAV, (p, view) -> target.back().accept(p));
        dialogs.show(player, target.title(), body, List.of(), buttons, 2, NAV, exit);
    }

    private void levels(Player player, Target target, HackSetting setting) {
        HackSettings settings = target.current().apply(player);
        double current = settings.get(setting);
        HackSetting.Level selected = setting.levelOf(current);
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(text(setting.description, TEXT), 300),
                DialogBody.plainMessage(DialogText.lines(
                        DialogText.detail("Now", setting.display(current), valueColor(setting, current)),
                        DialogText.muted("Looks suspicious above " + HackSetting.number(setting.warning) + unitSuffix(setting))), 300));
        List<ActionButton> buttons = new ArrayList<>();
        for (HackSetting.Level level : setting.levels()) {
            double value = setting.value(level);
            Component label = text(level.label, level == HackSetting.Level.OFF ? MUTED : TEXT);
            if (level != HackSetting.Level.OFF) label = label.append(text("  " + setting.display(value), valueColor(setting, value)));
            if (level == selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            buttons.add(dialogs.button(player, label, null, admin, 260,
                    (p, view) -> apply(p, target, target.current().apply(p).with(setting, value), setting.label + ": " + setting.display(value) + ".")));
        }
        buttons.add(dialogs.button(player, DialogIcon.SETTINGS.label("Custom Value", TEXT), DialogText.muted("Pick an exact value with a slider."), admin, 260,
                (p, view) -> custom(p, target, setting)));
        dialogs.show(player, setting.label, body, List.of(), buttons, 1, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, admin, NAV, (p, view) -> grid(p, target)));
    }

    private void custom(Player player, Target target, HackSetting setting) {
        double current = target.current().apply(player).get(setting);
        String unit = switch (setting.unit) { case "%" -> "%%"; case "x" -> "x"; case "blocks" -> " blocks"; default -> " " + setting.unit; };
        DialogInput slider = DialogInput.numberRange("value", text(setting.label, TEXT), (float) setting.min, (float) setting.max)
                .initial((float) current).step((float) setting.step()).width(300).labelFormat("%s: %s" + unit).build();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, admin, NAV, (p, view) -> levels(p, target, setting)));
        buttons.add(dialogs.button(player, DialogIcon.SAVE.label("Apply", ACCENT), null, admin, NAV, (p, view) -> {
            Float value = view.getFloat("value");
            if (value == null) throw new IllegalArgumentException("Choose a value.");
            double exact = setting.validate(Double.parseDouble(Float.toString(value)));
            apply(p, target, target.current().apply(p).with(setting, exact), setting.label + ": " + setting.display(exact) + ".");
        }));
        dialogs.show(player, setting.label, List.of(DialogBody.plainMessage(DialogText.muted(
                "Normal is " + normal(setting) + ". Suspicious above " + HackSetting.number(setting.warning) + unitSuffix(setting) + "."), 300)),
                List.of(slider), buttons, 2, NAV, null);
    }

    private void apply(Player player, Target target, HackSettings settings, String message) {
        target.save().accept(player, settings);
        Sounds.CLICK.play(player);
        player.sendActionBar(text(message, ACCENT));
        grid(player, target);
    }

    static Component status(HackSettings settings) {
        int active = settings.active().size(), risky = settings.warnings().size();
        if (active == 0) return text("All hacks are off", MUTED);
        Component count = text(active + (active == 1 ? " hack on" : " hacks on"), ACCENT);
        return count.append(text("  ·  ", MUTED)).append(risky == 0 ? text("Looks legit", SUCCESS)
                : text(risky == 1 ? "1 might look suspicious" : risky + " might look suspicious", WARNING));
    }

    static TextColor valueColor(HackSetting setting, double value) {
        if (setting.isOff(value)) return MUTED;
        return value > setting.warning + 1e-9 ? WARNING : ACCENT;
    }

    private static String normal(HackSetting setting) {
        return setting.normal == 0 ? "off" : HackSetting.number(setting.normal) + unitSuffix(setting);
    }

    private static String unitSuffix(HackSetting setting) {
        return switch (setting.unit) { case "x" -> "x"; case "%" -> "%"; case "blocks" -> " blocks"; default -> " " + setting.unit; };
    }
}
