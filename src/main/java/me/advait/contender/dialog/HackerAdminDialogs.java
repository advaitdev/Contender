package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import me.advait.contender.Contender;
import me.advait.contender.hacker.HackSettings;
import me.advait.contender.hacker.HackerService;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;

import static me.advait.contender.dialog.DialogPalette.*;

/** /hackers: the director's view of the secret hackers. */
public final class HackerAdminDialogs {
    private static final int WIDE = 300, NAV = 150, HALF = 150;
    private final Contender plugin;
    private final Dialogs dialogs;

    public HackerAdminDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    public void open(Player player) {
        HackerService hackers = plugin.getHackers();
        List<DialogBody> body = new ArrayList<>();
        Component names = Component.empty();
        if (hackers.hackers().isEmpty()) names = DialogText.muted("No hackers picked");
        else {
            boolean first = true;
            for (var entry : hackers.hackers().entrySet()) {
                if (!first) names = names.append(Component.newline());
                first = false;
                OfflinePlayer offline = Bukkit.getOfflinePlayer(entry.getKey());
                names = names.append(StringUtil.resolvedHead(entry.getKey(), entry.getValue().name(), offline.getPlayerProfile().getProperties()))
                        .append(text(" " + entry.getValue().name(), hackers.isHacker(entry.getKey()) ? TEXT : MUTED));
                HackSettings settings = hackers.mode() == HackerService.Mode.SELF ? entry.getValue().own() : hackers.plan(entry.getKey());
                names = names.append(text("  " + settings.summary(), MUTED));
            }
        }
        body.add(DialogBody.plainMessage(names, 360));
        body.add(DialogBody.plainMessage(DialogText.lines(
                DialogText.detail("Who picks the hacks", hackers.mode() == HackerService.Mode.SELF ? "Each hacker" : "You, for each event"),
                hackers.mode() == HackerService.Mode.DIRECTOR
                        ? DialogText.detail("Hacks", hackers.planActive() ? "On now" : "Turn on when the next event starts", hackers.planActive() ? SUCCESS : MUTED)
                        : DialogText.muted("Hackers change their hacks with /hacks.")), 360));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(player, DialogIcon.PLAYERS, "Choose Hackers", ACCENT, "Pick who is secretly hacking.", p -> choose(p, new LinkedHashSet<>(hackers.hackers().keySet()), 0)));
        if (hackers.mode() == HackerService.Mode.DIRECTOR) {
            buttons.add(button(player, DialogIcon.SETTINGS, "Hacks for This Event", TEXT, "Choose the hacks every hacker gets.", this::sharedPlan));
            if (hackers.hackers().size() > 1) buttons.add(button(player, DialogIcon.NAME, "Different Hacks per Hacker", TEXT,
                    "Give one hacker their own set.", this::perHacker));
            buttons.add(button(player, hackers.planActive() ? DialogIcon.PAUSE : DialogIcon.NEXT,
                    hackers.planActive() ? "Turn Hacks Off Now" : "Turn Hacks On Now", TEXT,
                    "Hacks also switch on and off with each event.", p -> { plugin.getHackers().activatePlan(!plugin.getHackers().planActive()); open(p); }));
        }
        buttons.add(button(player, DialogIcon.REFRESH, hackers.mode() == HackerService.Mode.SELF ? "Let Me Pick Each Event's Hacks" : "Let Hackers Pick Their Own",
                TEXT, "Switch who controls the hacks.", p -> {
                    plugin.getHackers().setMode(plugin.getHackers().mode() == HackerService.Mode.SELF ? HackerService.Mode.DIRECTOR : HackerService.Mode.SELF);
                    open(p);
                }));
        buttons.add(button(player, DialogIcon.SKULL, "Sabotage Settings", TEXT, "Turn sabotages on and choose which ones hackers can use.",
                p -> new SabotageDialogs(plugin).settings(p, this::open)));
        dialogs.show(player, "Hacker Controls", body, List.of(), buttons, 1, NAV, null);
    }

    private ActionButton button(Player player, DialogIcon icon, String label, net.kyori.adventure.text.format.TextColor color, String hint,
                                java.util.function.Consumer<Player> action) {
        return dialogs.button(player, icon.label(label, color), DialogText.muted(hint), true, WIDE, (p, view) -> action.accept(p));
    }

    private void sharedPlan(Player player) {
        new HackEditor(plugin, true).grid(player, new HackEditor.Target("Hacks for This Event",
                List.of(DialogText.muted(plugin.getHackers().planActive() ? "Changes apply to the hackers right away."
                        : "These switch on when the next event starts.")),
                p -> plugin.getHackers().sharedPlan(), (p, settings) -> plugin.getHackers().setPlan(null, settings), this::open));
    }

    private void perHacker(Player player) {
        HackerService hackers = plugin.getHackers();
        List<ActionButton> buttons = new ArrayList<>();
        for (var entry : hackers.hackers().entrySet()) {
            UUID id = entry.getKey();
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            Component label = Component.textOfChildren(StringUtil.resolvedHead(id, entry.getValue().name(), offline.getPlayerProfile().getProperties()),
                    text(" " + entry.getValue().name(), TEXT), text(hackers.hasOwnPlan(id) ? "  own set" : "  shared set", MUTED));
            buttons.add(dialogs.button(player, label, DialogText.muted(hackers.plan(id).summary()), true, WIDE, (p, view) -> planFor(p, id, entry.getValue().name())));
        }
        dialogs.show(player, "Per-Hacker Hacks", List.of(DialogBody.plainMessage(DialogText.muted("Choose a hacker to give them their own set."), 320)),
                List.of(), buttons, 1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> open(p)));
    }

    private void planFor(Player player, UUID hacker, String name) {
        List<HackEditor.Extra> extras = List.of(new HackEditor.Extra(DialogIcon.REFRESH, "Use the Shared Set", "Remove " + name + "'s own set.",
                p -> { plugin.getHackers().clearOwnPlan(hacker); perHacker(p); }));
        new HackEditor(plugin, true).grid(player, new HackEditor.Target(name + "'s Hacks", List.of(DialogText.muted("Only " + name + " gets these.")),
                p -> plugin.getHackers().plan(hacker), (p, settings) -> plugin.getHackers().setPlan(hacker, settings), this::perHacker, extras));
    }

    /** Toggle contestants in or out of the selection, then save it. */
    private void choose(Player player, Set<UUID> chosen, int requestedPage) {
        List<Player> candidates = Bukkit.getOnlinePlayers().stream()
                .filter(p -> plugin.getRoleManager().getRole(p.getUniqueId()) == PlayerRole.CONTESTANT)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).map(p -> (Player) p).toList();
        int perPage = 12, pages = Math.max(1, (candidates.size() + perPage - 1) / perPage), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (Player candidate : candidates.subList(page * perPage, Math.min(candidates.size(), (page + 1) * perPage))) {
            UUID id = candidate.getUniqueId();
            boolean selected = chosen.contains(id);
            Component label = Component.textOfChildren(StringUtil.getPlayerHead(candidate), text(" " + candidate.getName(), selected ? ACCENT : TEXT));
            if (selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            buttons.add(dialogs.button(player, label, DialogText.muted(selected ? "Click to remove." : "Click to add."), true, HALF, (p, view) -> {
                if (!chosen.remove(id)) chosen.add(id);
                choose(p, chosen, page);
            }));
        }
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, true, HALF, (p, view) -> choose(p, chosen, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, true, HALF, (p, view) -> choose(p, chosen, page + 1)) : null, HALF);
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, HALF, (p, view) -> open(p)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), DialogText.muted("Everyone online sees whether they were picked."), true, HALF, (p, view) -> {
                    List<OfflinePlayer> picked = chosen.stream().map(Bukkit::getOfflinePlayer).toList();
                    plugin.getHackers().pick(picked);
                    Dialogs.tell(p, picked.isEmpty() ? "Hacker selection cleared." : "Hackers saved: " + String.join(", ", picked.stream().map(OfflinePlayer::getName).toList()) + ".");
                    open(p);
                }), HALF);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.muted(candidates.isEmpty() ? "No contestants are online." : "Click players to pick them. Save tells everyone online whether they were picked."), 320));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 320));
        dialogs.show(player, "Choose Hackers", body, List.of(), buttons, 2, NAV, null);
    }
}
