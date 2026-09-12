package me.advait.contender.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.advait.contender.Contender;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class Dialogs {
    private final Contender plugin;
    public Dialogs(Contender plugin) { this.plugin = plugin; }
    public void show(Player player, String title, String body, List<DialogInput> inputs, List<ActionButton> buttons) {
        show(player, title, DialogText.muted(body), inputs, buttons);
    }
    public void show(Player player, String title, Component body, List<DialogInput> inputs, List<ActionButton> buttons) {
        show(player, title, List.of(DialogBody.plainMessage(body, 440)), inputs, buttons);
    }
    public void show(Player player, String title, List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons) {
        show(player, title, body, inputs, buttons, 2, 220, null);
    }
    /** Centered category menus use one column; forms use a fixed two-button navigation row. */
    public void show(Player player, String title, List<DialogBody> body, List<DialogInput> inputs,
                     List<ActionButton> buttons, int columns, int closeWidth, ActionButton footer) {
        // Paper reserves "id" for its callback UUID.
        if (inputs.stream().anyMatch(input -> input.key().equals("id"))) {
            throw new IllegalArgumentException("Dialog input key 'id' is reserved for Paper callbacks.");
        }
        ActionButton exit = footer == null ? ActionButton.builder(DialogPalette.text("Close", DialogPalette.MUTED)).width(closeWidth)
                .action(DialogAction.staticAction(ClickEvent.callback(
                        audience -> runCallback(player, audience, false, Player::closeDialog), callbackOptions()))).build() : footer;
        List<DialogBody> styledBody = body.stream().map(part -> part instanceof io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody plain
                ? DialogBody.plainMessage(DialogPalette.regular(plain.contents()), plain.width()) : part).toList();
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(DialogText.heading(title)).body(styledBody)
                        .inputs(inputs).pause(false).canCloseWithEscape(true).afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(buttons.isEmpty() ? DialogType.notice(exit) : DialogType.multiAction(buttons, exit, columns))));
    }
    /** Adds a complete navigation row below the other actions, with Previous left and Next right. */
    public static void navigationRow(List<ActionButton> buttons, ActionButton previous, ActionButton next) {
        navigationRow(buttons, previous, next, 220);
    }
    public static void navigationRow(List<ActionButton> buttons, ActionButton previous, ActionButton next, int width) {
        if (previous == null && next == null) return;
        if (buttons.size() % 2 != 0) buttons.add(placeholder(width));
        buttons.add(previous == null ? placeholder(width) : previous);
        buttons.add(next == null ? placeholder(width) : next);
    }
    private static ActionButton placeholder(int width) {
        return ActionButton.builder(DialogPalette.text("---", DialogPalette.MUTED)).width(width).build();
    }
    public ActionButton button(Player owner, String label, BiConsumer<Player, DialogResponseView> action) {
        return button(owner, label, true, action);
    }
    public ActionButton button(Player owner, String label, boolean admin, BiConsumer<Player, DialogResponseView> action) {
        return button(owner, DialogIcon.INFO.label(label), admin, action);
    }
    public ActionButton button(Player owner, DialogIcon icon, String label, BiConsumer<Player, DialogResponseView> action) {
        return button(owner, icon, label, true, action);
    }
    public ActionButton button(Player owner, DialogIcon icon, String label, boolean admin, BiConsumer<Player, DialogResponseView> action) {
        var color = switch (icon) {
            case SAVE, NEXT -> DialogPalette.ACCENT;
            case CLOSE -> DialogPalette.DANGER;
            case BACK -> DialogPalette.MUTED;
            default -> DialogPalette.TEXT;
        };
        return button(owner, icon.label(label, color), admin, action);
    }
    public ActionButton button(Player owner, Component label, boolean admin, BiConsumer<Player, DialogResponseView> action) {
        return button(owner, label, null, admin, action);
    }
    public ActionButton button(Player owner, Component label, Component tooltip, boolean admin, BiConsumer<Player, DialogResponseView> action) {
        return button(owner, label, tooltip, admin, 220, action);
    }
    public ActionButton button(Player owner, Component label, Component tooltip, boolean admin, int width, BiConsumer<Player, DialogResponseView> action) {
        return ActionButton.builder(DialogPalette.regular(label)).width(width).tooltip(tooltip == null ? null : DialogPalette.regular(tooltip)).action(DialogAction.customClick((view, audience) ->
                runCallback(owner, audience, admin, player -> action.accept(player, view)), callbackOptions())).build();
    }
    private static ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build();
    }
    private void runCallback(Player owner, Audience audience, boolean admin, Consumer<Player> action) {
        if (!(audience instanceof Player player) || !player.getUniqueId().equals(owner.getUniqueId())) return;
        Runnable callback = () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            if (admin && !player.hasPermission("contender.master")) { error(player, "You don't have permission to change this."); return; }
            try { action.accept(player); }
            catch (Exception failure) { error(player, message(failure)); }
        };
        if (Bukkit.isPrimaryThread()) callback.run();
        else plugin.getServer().getScheduler().runTask(plugin, callback);
    }
    public static DialogInput text(String key, String label, String initial, int length) {
        return DialogInput.text(key, Component.text(label)).initial(initial).maxLength(length).width(300).build();
    }
    public static DialogInput number(String key, String label, int initial, int min, int max, int step) {
        return DialogInput.numberRange(key, Component.text(label), min, max).initial((float) initial).step((float) step).width(300).build();
    }
    public static SingleOptionDialogInput.OptionEntry option(String id, String label, boolean selected) {
        return SingleOptionDialogInput.OptionEntry.create(id, DialogPalette.text(label, DialogPalette.TEXT), selected);
    }
    public static int number(DialogResponseView view, String key, int min, int max) {
        Float value = view.getFloat(key);
        if (value == null || !Float.isFinite(value) || value < min || value > max || value != Math.rint(value)) {
            throw new IllegalArgumentException("Choose a whole number from " + min + " to " + max + ".");
        }
        return value.intValue();
    }
    public static String text(DialogResponseView view, String key) {
        String value = view.getText(key);
        if (value == null) throw new IllegalArgumentException("Fill in the missing field.");
        return value.strip();
    }
    public static String message(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)) failure = failure.getCause();
        return failure.getMessage() == null ? "Something went wrong. Check the server log." : failure.getMessage();
    }
    public static void error(Player player, String text) { player.sendMessage(Component.text(text, NamedTextColor.RED)); }
    public static void tell(Player player, String text) { player.sendMessage(Component.text(text, NamedTextColor.GREEN)); }
}
