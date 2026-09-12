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

public final class HackerDialogs {
    private record Session(UUID selection) { }

    private final HackerManager hackers;
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public HackerDialogs(Contender plugin, HackerManager hackers) {
        this.plugin = plugin;
        this.hackers = hackers;
        dialogs = new Dialogs(plugin);
    }

    public void open(Player player) {
        if (!hackers.isHacker(player.getUniqueId())) {
            player.closeDialog(); Dialogs.error(player, "Only selected hackers can open this menu."); return;
        }
        var profile = hackers.profile(player.getUniqueId());
        var session = new Session(profile.selection());
        sessions.put(player.getUniqueId(), session);
        List<DialogInput> inputs = new ArrayList<>();
        for (HackSetting setting : HackSetting.values()) {
            Component label = setting.icon.label(setting.label, DialogPalette.TEXT)
                    .append(DialogPalette.text(" [" + setting.display(setting.warning) + "]", DialogPalette.MUTED));
            String suffix = switch (setting.unit) { case "%" -> "%%"; case "blocks" -> " blocks"; default -> setting.unit; };
            inputs.add(DialogInput.numberRange(setting.key(), DialogPalette.regular(label), (float) setting.min, (float) setting.max)
                    .initial((float) profile.settings().get(setting)).step(step(setting)).width(300)
                    .labelFormat("%s: %s" + suffix).build());
        }
        // A notice's Close button is also its Escape action, and includes all current input values.
        ActionButton close = dialogs.button(player, DialogPalette.text("Close", DialogPalette.MUTED), null, false, 150,
                (p, response) -> {
                    check(p, session);
                    HackSettings settings = read(response);
                    hackers.update(p, session.selection(), settings);
                    forget(p);
                    p.closeDialog();
                    Dialogs.tell(p, "Hacks applied.");
                });
        dialogs.show(player, "Hacks", List.of(
                DialogBody.plainMessage(DialogPalette.text("Close this menu or press Escape to apply your changes.", DialogPalette.MUTED), 300),
                DialogBody.plainMessage(DialogPalette.text("Hacks stay active everywhere, including the lobby.", DialogPalette.SUCCESS), 300),
                DialogBody.plainMessage(DialogIcon.INFO.label("Values above the limits in brackets may look blatant.", DialogPalette.ACCENT), 300)),
                inputs, List.of(), 1, 150, close);
    }

    public void forget(Player player) { sessions.remove(player.getUniqueId()); }

    public void clear() {
        Set<UUID> open = Set.copyOf(sessions.keySet());
        sessions.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) if (open.contains(player.getUniqueId())) player.closeDialog();
    }

    private void check(Player player, Session session) {
        var profile = hackers.profile(player.getUniqueId());
        if (sessions.get(player.getUniqueId()) != session || !hackers.isHacker(player.getUniqueId())
                || profile == null || !profile.selection().equals(session.selection())) {
            throw new IllegalStateException("This menu has expired. Open /hacks again.");
        }
    }

    private static float step(HackSetting setting) {
        return switch (setting) {
            case RESISTANCE, ANTI_KNOCKBACK -> 1f;
            case MOVEMENT_SPEED, JUMP_STRENGTH -> .01f;
            default -> .05f;
        };
    }

    private static HackSettings read(DialogResponseView response) {
        var values = new EnumMap<HackSetting, Double>(HackSetting.class);
        for (HackSetting setting : HackSetting.values()) {
            Float value = response.getFloat(setting.key());
            if (value == null || !Float.isFinite(value)) throw new IllegalArgumentException("Choose a value for " + setting.label + ".");
            // Use the slider's decimal representation, avoiding float noise at limits such as 0.6.
            values.put(setting, setting.validate(Double.parseDouble(Float.toString(value))));
        }
        return new HackSettings(values);
    }
}
