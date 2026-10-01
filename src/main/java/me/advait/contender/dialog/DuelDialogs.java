package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.duel.DuelSettings;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;

import static me.advait.contender.dialog.DialogPalette.*;

public final class DuelDialogs {
    private static final int FORM_WIDTH = 300, NAV_WIDTH = 150, TEAM_WIDTH = 220, PAGE_SIZE = 8;
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Set<DuelSetup> started = Collections.newSetFromMap(new IdentityHashMap<>());

    /** The choices made so far in /duel. */
    static final class DuelSetup {
        me.advait.contender.map.ArenaMap map;
        me.advait.contender.kit.Kit kit;
        int wins = 2, sortSeconds = 10;
        boolean freeForAll;
        final List<UUID> team1 = new ArrayList<>(), team2 = new ArrayList<>();
        boolean has(UUID id) { return team1.contains(id) || team2.contains(id); }
        Set<UUID> all() { Set<UUID> all = new LinkedHashSet<>(team1); all.addAll(team2); return all; }
    }
    private record Candidate(UUID id, String name, Component head, boolean available) { }
    public DuelDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    private ActionButton action(Player player, DialogIcon icon, String label, TextColor color, int width,
                                BiConsumer<Player, DialogResponseView> callback) {
        return dialogs.button(player, icon.label(label, color), null, true, width, callback);
    }
    private ActionButton back(Player player, int width, java.util.function.Consumer<Player> callback) {
        return action(player, DialogIcon.BACK, "Back", MUTED, width, (p, view) -> callback.accept(p));
    }
    private DialogBody body(Component component) { return DialogBody.plainMessage(component, 320); }
    public void open(Player player) {
        if (plugin.getVotes().isActive()) throw new IllegalStateException("Wait for the vote to finish before starting a duel.");
        details(player, new DuelSetup());
    }
    private void open(Player player, DuelSetup setup) { details(player, setup); }
    private void details(Player player, DuelSetup setup) {
        var maps = plugin.getMapManager().getMaps().stream().filter(m -> m.isComplete()).toList();
        var kits = new ArrayList<>(plugin.getKitManager().getKits());
        var requirements = new SetupRequirements(maps.size(), kits.size());
        if (!requirements.ready()) {
            dialogs.show(player, "Before You Begin", List.of(body(requirements.body())), List.of(), List.of(
                    action(player, DialogIcon.REFRESH, "Check Again", ACCENT, FORM_WIDTH, (p, view) -> details(p, setup)),
                    action(player, DialogIcon.MAP, "Maps", TEXT, FORM_WIDTH, (p, view) -> new ArenaDialogs(plugin).open(p)),
                    action(player, DialogIcon.DUEL, "Edit Kits", TEXT, FORM_WIDTH, (p, view) -> editKits(p, setup))), 1, NAV_WIDTH, null);
            return;
        }
        if (setup.map == null || !maps.contains(setup.map)) setup.map = maps.getFirst();
        if (setup.kit == null || !kits.contains(setup.kit)) setup.kit = kits.getFirst();
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("map", DialogIcon.MAP.label("Map"), maps.stream().map(m -> Dialogs.option(m.getId(),
                        m.getDisplayName() + " (" + plugin.getArenas().ready(m.getId()) + " ready)", m == setup.map)).toList()).width(FORM_WIDTH).build(),
                DialogInput.singleOption("kit", text("Kit", TEXT), kits.stream().map(k -> SingleOptionDialogInput.OptionEntry.create(
                        k.getId(), KitIcons.label(k), k == setup.kit)).toList()).width(FORM_WIDTH).build(),
                DialogInput.singleOption("mode", DialogIcon.PLAYERS.label("Mode"), List.of(
                        Dialogs.option("teams", "Two Teams", !setup.freeForAll),
                        Dialogs.option("ffa", "Free for All", setup.freeForAll))).width(FORM_WIDTH).build(),
                DialogInput.numberRange("wins", DialogIcon.DUEL.label("Round Wins Needed"), 1, 8)
                        .initial((float) setup.wins).step(1f).width(FORM_WIDTH).build(),
                DialogInput.numberRange("delay", DialogIcon.REFRESH.label("Time to Arrange Items"), 5, 60)
                        .initial((float) setup.sortSeconds).step(1f).width(FORM_WIDTH).labelFormat("%s: %ss").build());
        var actions = new ArrayList<ActionButton>();
        Dialogs.navigationRow(actions,
                action(player, DialogIcon.SETTINGS, "Setup Tools", MUTED, NAV_WIDTH, (p, view) -> { readDetails(view, setup); tools(p, setup); }),
                action(player, DialogIcon.NEXT, "Next: Players", ACCENT, NAV_WIDTH, (p, view) -> { readDetails(view, setup); players(p, setup, 0); }));
        dialogs.show(player, "New Duel", List.of(body(DialogText.muted("Choose a map, kit, and match length.\nEach duel uses its own arena copy."))), inputs, actions, 2, NAV_WIDTH, null);
    }
    private void readDetails(DialogResponseView view, DuelSetup setup) {
        var map = plugin.getMapManager().getMap(Dialogs.text(view, "map"));
        var kit = plugin.getKitManager().getKit(Dialogs.text(view, "kit"));
        if (map == null || !map.isComplete() || kit == null) throw new IllegalArgumentException("Choose a saved map and kit.");
        int wins = Dialogs.number(view, "wins", 1, 8), delay = Dialogs.number(view, "delay", 5, 60);
        String mode = Dialogs.text(view, "mode");
        if (!mode.equals("teams") && !mode.equals("ffa")) throw new IllegalArgumentException("Choose a duel mode.");
        setup.map = map; setup.kit = kit; setup.wins = wins; setup.sortSeconds = delay;
        setup.freeForAll = mode.equals("ffa");
    }
    private void tools(Player player, DuelSetup setup) {
        dialogs.show(player, "Duel Setup Tools", List.of(body(DialogText.muted("Create maps and edit the kits used in duels."))), List.of(), List.of(
                action(player, DialogIcon.MAP, "Maps", TEXT, FORM_WIDTH, (p, view) -> new ArenaDialogs(plugin).open(p)),
                action(player, DialogIcon.DUEL, "Edit Kits", TEXT, FORM_WIDTH, (p, view) -> editKits(p, setup))), 1, NAV_WIDTH, back(player, NAV_WIDTH, p -> details(p, setup)));
    }
    private void editKits(Player player, DuelSetup setup) {
        new KitDialogs(plugin).open(player, p -> open(p, setup));
    }
    private boolean available(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() && plugin.getRoleManager().isContestant(id)
                && !plugin.getRegistry().isPlaying(id) && !inEvent(id);
    }
    /** Entered in a running tournament or minigame, so a side match would hold it up. A paused one is fine. */
    private boolean inEvent(UUID id) {
        var tournament = plugin.getTournaments().current();
        if (tournament != null && tournament.isRunning() && tournament.isReserved(id)) return true;
        var game = plugin.getMinigames().current();
        return game != null && game.started() && !game.finished() && game.involves(id);
    }
    private List<Candidate> candidates(DuelSetup setup) {
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId(); boolean available = available(id);
            if (available || setup.has(id)) candidates.put(id, new Candidate(id, player.getName(), StringUtil.getPlayerHead(player), available));
        }
        for (UUID id : setup.all()) if (!candidates.containsKey(id)) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            candidates.put(id, new Candidate(id, name == null ? "Offline Player" : name, StringUtil.getPlayerHead(id), false));
        }
        return candidates.values().stream().sorted(Comparator.comparing(Candidate::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    private void players(Player player, DuelSetup setup, int requestedPage) {
        if (setup.freeForAll) { review(player, setup, 0); return; }
        var candidates = candidates(setup);
        int pages = Math.max(1, (candidates.size() + PAGE_SIZE - 1) / PAGE_SIZE), page = Math.clamp(requestedPage, 0, pages - 1);
        var buttons = new ArrayList<ActionButton>();
        buttons.add(ActionButton.builder(regular(DialogIcon.PLAYERS.label("Team 1 (" + setup.team1.size() + ")", ACCENT))).width(TEAM_WIDTH).build());
        buttons.add(ActionButton.builder(regular(DialogIcon.PLAYERS.label("Team 2 (" + setup.team2.size() + ")", ACCENT))).width(TEAM_WIDTH).build());
        for (int i = page * PAGE_SIZE; i < Math.min(candidates.size(), (page + 1) * PAGE_SIZE); i++) {
            buttons.add(candidateButton(player, setup, candidates.get(i), 1, page));
            buttons.add(candidateButton(player, setup, candidates.get(i), 2, page));
        }
        buttons.add(back(player, TEAM_WIDTH, p -> details(p, setup)));
        buttons.add(action(player, DialogIcon.NEXT, "Next: Review", ACCENT, TEAM_WIDTH, (p, view) -> review(p, setup, page)));
        Dialogs.navigationRow(buttons,
                page > 0 ? action(player, DialogIcon.BACK, "Previous Page", TEXT, TEAM_WIDTH, (p, view) -> players(p, setup, page - 1)) : null,
                page + 1 < pages ? action(player, DialogIcon.NEXT, "Next Page", TEXT, TEAM_WIDTH, (p, view) -> players(p, setup, page + 1)) : null);
        dialogs.show(player, "Choose Players", List.of(body(DialogText.muted(candidates.isEmpty() ? "No contestants are available right now."
                        : "Click a player under their team.\nClick a selected player again to remove them.")), body(DialogText.page(page + 1, pages))), List.of(), buttons, 2, NAV_WIDTH, null);
    }
    private ActionButton candidateButton(Player owner, DuelSetup setup, Candidate candidate, int side, int page) {
        var team = side == 1 ? setup.team1 : setup.team2;
        var other = side == 1 ? setup.team2 : setup.team1;
        boolean selected = team.contains(candidate.id()), taken = other.contains(candidate.id());
        Component label = Component.textOfChildren(candidate.head(), text(" " + candidate.name(), selected ? ACCENT : taken || !candidate.available() ? MUTED : TEXT));
        if (selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
        String hint = selected ? "Remove from Team " + side + "." : taken ? "Selected for Team " + (side == 1 ? 2 : 1) + ". Remove them there first."
                : !candidate.available() ? "This player is no longer available." : "Add to Team " + side + ".";
        if (!selected && (taken || !candidate.available())) return ActionButton.builder(DialogPalette.regular(label)).tooltip(DialogText.muted(hint)).width(TEAM_WIDTH).build();
        return dialogs.button(owner, label, DialogText.muted(hint), true, TEAM_WIDTH, (p, view) -> {
            if (started.contains(setup)) throw new IllegalStateException("This duel has already started.");
            if (team.contains(candidate.id())) team.remove(candidate.id());
            else {
                if (other.contains(candidate.id())) throw new IllegalArgumentException(candidate.name() + " is already on the other team.");
                if (!available(candidate.id())) throw new IllegalArgumentException(candidate.name() + " is no longer available.");
                team.add(candidate.id());
            }
            players(p, setup, page);
        });
    }
    private Component roster(DuelSetup setup, int side) {
        var team = side == 1 ? setup.team1 : setup.team2;
        Component line = DialogIcon.PLAYERS.label("Team " + side + " (" + team.size() + ")", ACCENT);
        for (var candidate : candidates(setup)) if (team.contains(candidate.id())) line = line.append(Component.newline())
                .append(candidate.head()).append(text(" " + candidate.name(), TEXT));
        return line;
    }
    private void review(Player player, DuelSetup setup, int page) {
        if (!setup.freeForAll && (setup.team1.isEmpty() || setup.team2.isEmpty())) {
            throw new IllegalArgumentException("Choose at least one player for each team.");
        }
        var contents = new ArrayList<DialogBody>();
        contents.add(body(DialogText.lines(DialogIcon.MAP.label(setup.map.getDisplayName()), KitIcons.label(setup.kit),
                DialogText.detail("Win condition", "First to " + setup.wins + (setup.wins == 1 ? " round win" : " round wins")),
                DialogText.detail("Time to arrange items", setup.sortSeconds + " seconds"))));
        if (setup.freeForAll) contents.add(body(DialogText.muted("All free contestants join when you start.\nPlayers in a running event are left out.")));
        else { contents.add(body(roster(setup, 1))); contents.add(body(roster(setup, 2))); }
        dialogs.show(player, "Review Duel", contents, List.of(), List.of(action(player, DialogIcon.DUEL, "Start Duel", ACCENT, FORM_WIDTH, (p, view) -> {
            if (started.contains(setup)) throw new IllegalStateException("This duel has already started.");
            List<List<UUID>> sides = new ArrayList<>();
            if (setup.freeForAll) {
                for (Player online : Bukkit.getOnlinePlayers()) if (available(online.getUniqueId())) sides.add(List.of(online.getUniqueId()));
                if (sides.size() < 2) throw new IllegalStateException("Free for all needs at least two free contestants.");
            } else { sides.add(List.copyOf(setup.team1)); sides.add(List.copyOf(setup.team2)); }
            plugin.getDuels().start(new DuelSettings(setup.map, setup.kit, setup.wins, setup.sortSeconds, setup.freeForAll,
                    Math.clamp(plugin.getConfig().getInt("matches.reconnect-seconds", 60), 0, 600)), sides, null, null, null);
            started.add(setup);
            p.closeDialog(); Dialogs.tell(p, "Duel started.");
        })), 1, NAV_WIDTH, back(player, NAV_WIDTH, p -> {
            if (setup.freeForAll) details(p, setup); else players(p, setup, page);
        }));
    }
}
