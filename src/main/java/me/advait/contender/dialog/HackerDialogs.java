package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import me.advait.contender.Contender;
import me.advait.contender.hacker.HackSettings;
import me.advait.contender.hacker.HackerService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static me.advait.contender.dialog.DialogPalette.*;

/** /hacks: what a hacker sees. */
public final class HackerDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;

    public HackerDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    public void open(Player player) {
        HackerService hackers = plugin.getHackers();
        if (!hackers.isHacker(player.getUniqueId())) {
            player.closeDialog();
            Dialogs.error(player, "Only the hackers can open this menu.");
            return;
        }
        if (hackers.mode() == HackerService.Mode.SELF) {
            List<Component> header = new ArrayList<>();
            header.add(DialogText.muted("Only you can see this. Changes apply right away."));
            List<HackEditor.Extra> extras = plugin.getSabotage().available(player)
                    ? List.of(new HackEditor.Extra(DialogIcon.SKULL, "Sabotage", "Change the rules for everyone in this event.", p -> new SabotageDialogs(plugin).open(p)))
                    : List.of();
            new HackEditor(plugin, false).grid(player, new HackEditor.Target("Hacks", header,
                    p -> require(p).profile(p.getUniqueId()).own(),
                    (p, settings) -> require(p).setOwn(p, settings), null, extras));
            return;
        }
        directed(player);
    }

    /** When the director picks the hacks, the hacker only sees them. */
    private void directed(Player player) {
        HackerService hackers = plugin.getHackers();
        HackSettings plan = hackers.plan(player.getUniqueId());
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.muted("The director chooses your hacks for each event.\nOnly you can see this."), 320));
        Component state = hackers.planActive()
                ? text("On now", SUCCESS)
                : text("Off until the next event starts", MUTED);
        body.add(DialogBody.plainMessage(state, 320));
        body.add(DialogBody.plainMessage(DialogText.lines(text(hackers.planActive() ? "Your hacks" : "Planned for next event", ACCENT),
                text(plan.active().isEmpty() ? "Nothing yet" : String.join("\n", plan.active().stream()
                        .map(setting -> setting.label + "  " + setting.display(plan.get(setting))).toList()), TEXT)), 320));
        List<ActionButton> buttons = new ArrayList<>();
        if (plugin.getSabotage().available(player)) {
            buttons.add(dialogs.button(player, DialogIcon.SKULL.label("Sabotage", ACCENT), DialogText.muted("Change the rules for everyone in this event."),
                    false, 300, (p, view) -> new SabotageDialogs(plugin).open(p)));
        }
        buttons.add(dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), null, false, 300, (p, view) -> open(p)));
        dialogs.show(player, "Hacks", body, List.of(), buttons, 1, 150, null);
    }

    private HackerService require(Player player) {
        HackerService hackers = plugin.getHackers();
        if (!hackers.isHacker(player.getUniqueId())) throw new IllegalStateException("You're no longer a hacker.");
        return hackers;
    }
}
