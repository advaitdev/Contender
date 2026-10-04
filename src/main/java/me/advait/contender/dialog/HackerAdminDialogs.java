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
    private static final String OFFLINE = "Offline";
    private final Contender plugin;
    private final Dialogs dialogs;
    /** Where Hacker Controls' Back goes when opened from a menu; null (from /hackers) shows Close. */
    private final java.util.function.Consumer<Player> back;

    public HackerAdminDialogs(Contender plugin) { this(plugin, null); }

    public HackerAdminDialogs(Contender plugin, java.util.function.Consumer<Player> back) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
        this.back = back;
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
                String status = status(entry.getKey());
                names = names.append(text("  " + (status == null ? settings.summary()
                        : status.equals(OFFLINE) ? OFFLINE + " · " + settings.summary() : status), MUTED));
            }
        }
        body.add(DialogBody.plainMessage(names, 360));
        body.add(DialogBody.plainMessage(DialogText.lines(
                DialogText.detail("Who picks the hacks", hackers.mode() == HackerService.Mode.SELF ? "Each hacker" : "You, for each event"),
                hackers.mode() == HackerService.Mode.DIRECTOR
                        ? DialogText.lines(DialogText.detail("Hacks", hackers.planActive() ? "On now" : "Turn on when the next event starts", hackers.planActive() ? SUCCESS : MUTED),
                                DialogText.detail("Between events", hackers.ownBetweenEvents() ? "Their own hacks" : "No hacks"))
                        : DialogText.muted("Hackers change their hacks with /hacks.")), 360));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(player, DialogIcon.PLAYERS, "Choose Hackers", ACCENT, "Pick or remove the secret hackers.", p -> choose(p, new LinkedHashSet<>(hackers.hackers().keySet()), 0)));
        if (hackers.mode() == HackerService.Mode.DIRECTOR) {
            buttons.add(button(player, DialogIcon.SETTINGS, "Hacks for This Event", TEXT, "Choose the hacks every hacker gets.", this::sharedPlan));
            if (hackers.hackers().size() > 1) buttons.add(button(player, DialogIcon.NAME, "Different Hacks per Hacker", TEXT,
                    "Give one hacker their own set.", this::perHacker));
            buttons.add(button(player, hackers.planActive() ? DialogIcon.PAUSE : DialogIcon.NEXT,
                    hackers.planActive() ? "Turn Hacks Off Now" : "Turn Hacks On Now", TEXT,
                    "Hacks also switch on and off with each event.", p -> { plugin.getHackers().activatePlan(!plugin.getHackers().planActive()); open(p); }));
        }
        buttons.add(button(player, DialogIcon.REFRESH, "Who Picks the Hacks", TEXT, "You for each event, or each hacker.", this::whoPicks));
        buttons.add(button(player, DialogIcon.SKULL, "Sabotage Settings", TEXT, "Turn sabotages on and choose which ones hackers can use.",
                p -> new SabotageDialogs(plugin).settings(p, this::open)));
        dialogs.show(player, "Hacker Controls", body, List.of(), buttons, 1, NAV, back == null ? null
                : dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> back.accept(p)));
    }

    /** Each hacker picks, or you pick each event's hacks (with hackers' own hacks, or none, between events). */
    private void whoPicks(Player player) {
        HackerService hackers = plugin.getHackers();
        record Choice(String label, String hint, HackerService.Mode mode, boolean ownBetween) { }
        List<Choice> choices = List.of(
                new Choice("Hackers Pick Their Own", "Each hacker sets their hacks with /hacks, any time.", HackerService.Mode.SELF, true),
                new Choice("You Pick, Their Own Between Events", "Your hacks switch on with each event. Between events, hackers use their own.", HackerService.Mode.DIRECTOR, true),
                new Choice("You Pick, No Hacks Between Events", "Your hacks switch on with each event. Between events, nobody has hacks.", HackerService.Mode.DIRECTOR, false));
        List<ActionButton> buttons = new ArrayList<>();
        for (Choice choice : choices) {
            boolean current = hackers.mode() == choice.mode() && (choice.mode() == HackerService.Mode.SELF || hackers.ownBetweenEvents() == choice.ownBetween());
            Component label = DialogIcon.PLAYERS.label(choice.label(), current ? ACCENT : TEXT);
            if (current) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            buttons.add(dialogs.button(player, label, DialogText.muted(choice.hint()), true, WIDE, (p, view) -> {
                if (plugin.getHackers().mode() != choice.mode()) plugin.getHackers().setMode(choice.mode());
                if (choice.mode() == HackerService.Mode.DIRECTOR) plugin.getHackers().setOwnBetweenEvents(choice.ownBetween());
                whoPicks(p);
            }));
        }
        dialogs.show(player, "Who Picks the Hacks", List.of(DialogBody.plainMessage(DialogText.muted("Hackers are told when this changes."), 320)),
                List.of(), buttons, 1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> open(p)));
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

    /** Someone the director can pick: every online contestant, plus everyone already picked wherever they are. */
    private record Candidate(UUID id, String name, Component head, String status) { }

    /** Why a picked hacker has no hacks right now, or null when they're in play. */
    private String status(UUID id) {
        PlayerRole role = plugin.getRoleManager().getRole(id);
        if (role != PlayerRole.CONTESTANT) return role.label() + ", so no hacks";
        return Bukkit.getPlayer(id) == null ? OFFLINE : null;
    }

    private List<Candidate> candidates() {
        Map<UUID, Candidate> all = new HashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (plugin.getRoleManager().getRole(online.getUniqueId()) == PlayerRole.CONTESTANT)
                all.put(online.getUniqueId(), new Candidate(online.getUniqueId(), online.getName(), StringUtil.getPlayerHead(online), null));
        }
        plugin.getHackers().hackers().forEach((id, profile) -> {
            Player online = Bukkit.getPlayer(id);
            Component head = online != null ? StringUtil.getPlayerHead(online)
                    : StringUtil.resolvedHead(id, profile.name(), Bukkit.getOfflinePlayer(id).getPlayerProfile().getProperties());
            all.put(id, new Candidate(id, online != null ? online.getName() : profile.name(), head, status(id)));
        });
        return all.values().stream().sorted(Comparator.comparing(Candidate::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Toggle players in or out of the selection, then save it. */
    private void choose(Player player, Set<UUID> chosen, int requestedPage) {
        List<Candidate> candidates = candidates();
        int perPage = 12, pages = Math.max(1, (candidates.size() + perPage - 1) / perPage), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        for (Candidate candidate : candidates.subList(page * perPage, Math.min(candidates.size(), (page + 1) * perPage))) {
            UUID id = candidate.id();
            boolean selected = chosen.contains(id);
            Component label = Component.textOfChildren(candidate.head(), text(" " + candidate.name(), selected ? ACCENT : candidate.status() == null ? TEXT : MUTED));
            if (selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
            String hint = selected ? "Click to remove." : "Click to add.";
            if (candidate.status() != null) hint = candidate.status() + ". " + hint;
            buttons.add(dialogs.button(player, label, DialogText.muted(hint), true, HALF, (p, view) -> {
                if (!chosen.remove(id)) chosen.add(id);
                choose(p, chosen, page);
            }));
        }
        if (!chosen.isEmpty()) Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.REFRESH.label("Clear All", TEXT), DialogText.muted("Unpicks everyone. Save to confirm."), true, HALF,
                        (p, view) -> choose(p, new LinkedHashSet<>(), page)), null, HALF);
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, true, HALF, (p, view) -> choose(p, chosen, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, true, HALF, (p, view) -> choose(p, chosen, page + 1)) : null, HALF);
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, HALF, (p, view) -> open(p)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), DialogText.muted("Everyone online sees whether they were picked."), true, HALF, (p, view) -> {
                    try {
                        plugin.getHackers().pick(chosen.stream().map(Bukkit::getOfflinePlayer).toList());
                    } catch (IllegalArgumentException failure) {
                        Dialogs.error(p, failure.getMessage());
                        choose(p, chosen, page);
                        return;
                    }
                    List<String> names = plugin.getHackers().hackers().values().stream().map(HackerService.Profile::name).toList();
                    Dialogs.tell(p, names.isEmpty() ? "Hacker selection cleared." : "Hackers saved: " + String.join(", ", names) + ".");
                    open(p);
                }), HALF);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(DialogText.muted(candidates.isEmpty() ? "No contestants are online."
                : "Click players to pick or remove them. Save tells everyone online whether they were picked."), 320));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 320));
        dialogs.show(player, "Choose Hackers", body, List.of(), buttons, 2, NAV, null);
    }
}
