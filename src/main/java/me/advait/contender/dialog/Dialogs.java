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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public final class Dialogs {
    private final Contender plugin;
    public Dialogs(Contender plugin) { this.plugin = plugin; }
    public void show(Player player, String title, String body, List<DialogInput> inputs, List<ActionButton> buttons) {
        List<ActionButton> actions = new ArrayList<>(buttons);
        ActionButton close = button(player, "Close", false, (p, view) -> p.closeDialog());
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title)).body(List.of(DialogBody.plainMessage(Component.text(body))))
                        .inputs(inputs).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                .type(DialogType.multiAction(actions, close, 2))));
    }
    public ActionButton button(Player owner, String label, BiConsumer<Player, DialogResponseView> action) {
        return button(owner, label, true, action);
    }
    public ActionButton button(Player owner, String label, boolean admin, BiConsumer<Player, DialogResponseView> action) {
        return ActionButton.builder(Component.text(label)).width(220).action(DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(owner.getUniqueId())) return;
            Runnable callback = () -> {
                if (!plugin.isEnabled() || !player.isOnline()) return;
                if (admin && !player.hasPermission("contender.master")) { error(player, "You don't have permission to change this."); return; }
                try { action.accept(player, view); }
                catch (Exception failure) { error(player, message(failure)); }
            };
            if (Bukkit.isPrimaryThread()) callback.run();
            else plugin.getServer().getScheduler().runTask(plugin, callback);
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build())).build();
    }
    public static DialogInput text(String key, String label, String initial, int length) {
        return DialogInput.text(key, Component.text(label)).initial(initial).maxLength(length).width(300).build();
    }
    public static DialogInput number(String key, String label, int initial, int min, int max, int step) {
        return DialogInput.numberRange(key, Component.text(label), min, max).initial((float) initial).step((float) step).width(300).build();
    }
    public static SingleOptionDialogInput.OptionEntry option(String id, String label, boolean selected) {
        return SingleOptionDialogInput.OptionEntry.create(id, Component.text(label), selected);
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
