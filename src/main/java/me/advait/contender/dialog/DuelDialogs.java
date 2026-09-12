package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.duel.DuelMode;
import me.advait.contender.duel.DuelSetup;
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
    public void open(Player player) { details(player, new DuelSetup(player.getUniqueId())); }
    public void open(Player player, DuelSetup setup) { details(player, setup); }
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
        if (setup.getSelectedMap() == null || !maps.contains(setup.getSelectedMap())) setup.setSelectedMap(maps.getFirst());
        if (setup.getSelectedKit() == null || !kits.contains(setup.getSelectedKit())) setup.setSelectedKit(kits.getFirst());
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("map", DialogIcon.MAP.label("Map"), maps.stream().map(m -> Dialogs.option(m.getId(),
                        m.getDisplayName() + " (" + plugin.getArenaManager().available(m.getId()) + " ready)", m == setup.getSelectedMap())).toList()).width(FORM_WIDTH).build(),
                DialogInput.singleOption("kit", DialogIcon.DUEL.label("Kit"), kits.stream().map(k -> SingleOptionDialogInput.OptionEntry.create(
                        k.getId(), KitIcons.label(k), k == setup.getSelectedKit())).toList()).width(FORM_WIDTH).build(),
                DialogInput.singleOption("mode", DialogIcon.PLAYERS.label("Mode"), List.of(
                        Dialogs.option("teams", "Two Teams", setup.getMode() == DuelMode.STANDARD),
                        Dialogs.option("ffa", "Free for All", setup.getMode() == DuelMode.FFA))).width(FORM_WIDTH).build(),
                DialogInput.numberRange("wins", DialogIcon.DUEL.label("Round Wins Needed"), 1, 8)
                        .initial((float) (setup.getRounds() / 2 + 1)).step(1f).width(FORM_WIDTH).build(),
                DialogInput.numberRange("delay", DialogIcon.REFRESH.label("Sorting Time (seconds)"), 5, 60)
                        .initial((float) setup.getPreRoundDelay()).step(1f).width(FORM_WIDTH).build());
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
        setup.setSelectedMap(map); setup.setSelectedKit(kit); setup.setRounds(wins * 2 - 1); setup.setPreRoundDelay(delay);
        setup.setMode(mode.equals("ffa") ? DuelMode.FFA : DuelMode.STANDARD);
    }
    private void tools(Player player, DuelSetup setup) {
        dialogs.show(player, "Duel Setup Tools", List.of(body(DialogText.muted("Create maps and edit the kits used in duels."))), List.of(), List.of(
                action(player, DialogIcon.MAP, "Maps", TEXT, FORM_WIDTH, (p, view) -> new ArenaDialogs(plugin).open(p)),
                action(player, DialogIcon.DUEL, "Edit Kits", TEXT, FORM_WIDTH, (p, view) -> editKits(p, setup))), 1, NAV_WIDTH, back(player, NAV_WIDTH, p -> details(p, setup)));
    }
    private void editKits(Player player, DuelSetup setup) {
        player.closeDialog();
        new KitDialogs(plugin).open(player, p -> open(p, setup));
    }
    private boolean available(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() && plugin.getDuelManager().isEligible(id)
                && !plugin.getDuelManager().isPlaying(id)
                && (plugin.getTournamentManager() == null || !plugin.getTournamentManager().isReserved(id));
    }
    private List<Candidate> candidates(DuelSetup setup) {
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId(); boolean available = available(id);
            if (available || setup.isPlayerInAnyTeam(id)) candidates.put(id, new Candidate(id, player.getName(), StringUtil.getPlayerHead(player), available));
        }
        for (UUID id : setup.getAllPlayers()) if (!candidates.containsKey(id)) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            candidates.put(id, new Candidate(id, name == null ? "Offline Player" : name, StringUtil.getPlayerHead(id), false));
        }
        return candidates.values().stream().sorted(Comparator.comparing(Candidate::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    private void players(Player player, DuelSetup setup, int requestedPage) {
        if (setup.getMode() == DuelMode.FFA) { review(player, setup, 0); return; }
        var candidates = candidates(setup);
        int pages = Math.max(1, (candidates.size() + PAGE_SIZE - 1) / PAGE_SIZE), page = Math.clamp(requestedPage, 0, pages - 1);
        var buttons = new ArrayList<ActionButton>();
        buttons.add(ActionButton.builder(regular(DialogIcon.PLAYERS.label("Team 1 (" + setup.getTeam1().size() + ")", ACCENT))).width(TEAM_WIDTH).build());
        buttons.add(ActionButton.builder(regular(DialogIcon.PLAYERS.label("Team 2 (" + setup.getTeam2().size() + ")", ACCENT))).width(TEAM_WIDTH).build());
        for (int i = page * PAGE_SIZE; i < Math.min(candidates.size(), (page + 1) * PAGE_SIZE); i++) {
            buttons.add(candidateButton(player, setup, candidates.get(i), 1, page));
            buttons.add(candidateButton(player, setup, candidates.get(i), 2, page));
        }
        buttons.add(back(player, TEAM_WIDTH, p -> details(p, setup)));
        buttons.add(action(player, DialogIcon.NEXT, "Next: Review", ACCENT, TEAM_WIDTH, (p, view) -> review(p, setup, page)));
        Dialogs.navigationRow(buttons,
                page > 0 ? action(player, DialogIcon.BACK, "Previous Page", MUTED, TEAM_WIDTH, (p, view) -> players(p, setup, page - 1)) : null,
                page + 1 < pages ? action(player, DialogIcon.NEXT, "Next Page", ACCENT, TEAM_WIDTH, (p, view) -> players(p, setup, page + 1)) : null);
        dialogs.show(player, "Choose Players", List.of(body(DialogText.muted(candidates.isEmpty() ? "No contestants are available right now."
                        : "Click a player under their team.\nClick a selected player again to remove them.")), body(DialogText.page(page + 1, pages))), List.of(), buttons, 2, NAV_WIDTH, null);
    }
    private ActionButton candidateButton(Player owner, DuelSetup setup, Candidate candidate, int side, int page) {
        var team = side == 1 ? setup.getTeam1() : setup.getTeam2();
        var other = side == 1 ? setup.getTeam2() : setup.getTeam1();
        boolean selected = team.hasPlayer(candidate.id()), taken = other.hasPlayer(candidate.id());
        Component label = Component.textOfChildren(candidate.head(), text(" " + candidate.name(), selected ? ACCENT : taken || !candidate.available() ? MUTED : TEXT));
        if (selected) label = label.append(Component.space()).append(DialogIcon.SAVE.sprite());
        String hint = selected ? "Remove from Team " + side + "." : taken ? "Selected for Team " + (side == 1 ? 2 : 1) + ". Remove them there first."
                : !candidate.available() ? "This player is no longer available." : "Add to Team " + side + ".";
        if (!selected && (taken || !candidate.available())) return ActionButton.builder(DialogPalette.regular(label)).tooltip(DialogText.muted(hint)).width(TEAM_WIDTH).build();
        return dialogs.button(owner, label, DialogText.muted(hint), true, TEAM_WIDTH, (p, view) -> {
            if (started.contains(setup)) throw new IllegalStateException("This duel has already started.");
            if (team.hasPlayer(candidate.id())) team.removePlayer(candidate.id());
            else {
                if (other.hasPlayer(candidate.id())) throw new IllegalArgumentException(candidate.name() + " is already on the other team.");
                if (!available(candidate.id())) throw new IllegalArgumentException(candidate.name() + " is no longer available.");
                team.addPlayer(candidate.id());
            }
            players(p, setup, page);
        });
    }
    private Component roster(DuelSetup setup, int side) {
        var team = side == 1 ? setup.getTeam1() : setup.getTeam2();
        Component line = DialogIcon.PLAYERS.label("Team " + side + " (" + team.size() + ")", ACCENT);
        for (var candidate : candidates(setup)) if (team.hasPlayer(candidate.id())) line = line.append(Component.newline())
                .append(candidate.head()).append(text(" " + candidate.name(), TEXT));
        return line;
    }
    private void review(Player player, DuelSetup setup, int page) {
        if (setup.getMode() != DuelMode.FFA && (setup.getTeam1().isEmpty() || setup.getTeam2().isEmpty())) {
            throw new IllegalArgumentException("Choose at least one player for each team.");
        }
        var contents = new ArrayList<DialogBody>();
        contents.add(body(DialogText.lines(DialogIcon.MAP.label(setup.getSelectedMap().getDisplayName()), KitIcons.label(setup.getSelectedKit()),
                DialogText.detail("Win condition", "First to " + (setup.getRounds() / 2 + 1) + " round wins"),
                DialogText.detail("Sorting", setup.getPreRoundDelay() + " seconds"))));
        if (setup.getMode() == DuelMode.FFA) contents.add(body(DialogText.muted("All available contestants join when you start.\nPlayers entered in a tournament are excluded.")));
        else { contents.add(body(roster(setup, 1))); contents.add(body(roster(setup, 2))); }
        dialogs.show(player, "Review Duel", contents, List.of(), List.of(action(player, DialogIcon.DUEL, "Start Duel", ACCENT, FORM_WIDTH, (p, view) -> {
            if (started.contains(setup)) throw new IllegalStateException("This duel has already started.");
            plugin.getDuelManager().startDuel(setup); started.add(setup);
            p.closeDialog(); Dialogs.tell(p, "Duel started.");
        })), 1, NAV_WIDTH, back(player, NAV_WIDTH, p -> {
            if (setup.getMode() == DuelMode.FFA) details(p, setup); else players(p, setup, page);
        }));
    }
    public void spectate(Player player) { spectate(player, 0); }
    private void spectate(Player player, int requestedPage) {
        var matches = plugin.getDuelManager().getActiveDuels().stream().filter(d -> d.getState().canAddSpectator()).toList();
        int pages = Math.max(1, (matches.size() + 7) / 8), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        List<DialogBody> body = new ArrayList<>();
        if (matches.isEmpty()) body.add(body(DialogText.muted("No matches are running.")));
        for (int i = page * 8; i < Math.min(page * 8 + 8, matches.size()); i++) {
            var duel = matches.get(i);
            String score = duel.getTeam1().getScore() + "-" + duel.getTeam2().getScore();
            Component label = compactSide(duel.getTeam1()).append(DialogText.muted(" vs "))
                    .append(compactSide(duel.getTeam2())).append(text("  " + score, ACCENT));
            Component tooltip = side(duel.getTeam1()).append(DialogText.muted(" vs ")).append(side(duel.getTeam2()))
                    .append(Component.newline()).append(text(score, ACCENT));
            buttons.add(dialogs.button(player, label, tooltip, false, FORM_WIDTH, (p, view) -> {
                plugin.getDuelManager().spectate(p, duel); p.closeDialog();
            }));
        }
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.SPAWN.label("Return to Lobby"), null, false, FORM_WIDTH, (p, view) -> { plugin.getLobbyManager().returnToLobby(p); p.closeDialog(); }),
                dialogs.button(player, DialogIcon.REFRESH.label("Refresh"), null, false, FORM_WIDTH, (p, view) -> spectate(p, page)), FORM_WIDTH);
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous Page", MUTED), null, false, FORM_WIDTH, (p, view) -> spectate(p, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next Page", ACCENT), null, false, FORM_WIDTH, (p, view) -> spectate(p, page + 1)) : null, FORM_WIDTH);
        if (pages > 1) body.add(body(DialogText.page(page + 1, pages)));
        dialogs.show(player, "Spectate Matches", body, List.of(), buttons, 2, NAV_WIDTH, null);
    }
    private Component compactSide(me.advait.contender.duel.DuelTeam team) {
        String name = team.getName();
        // Leave room for both names, heads, and score. The tooltip keeps every full name and tier.
        String shortName = name;
        while (me.advait.contender.tab.TabText.width(text(shortName, TEXT)) > 108 && shortName.length() > 1) {
            int end = shortName.endsWith("…") ? shortName.length() - 1 : shortName.length();
            shortName = shortName.substring(0, shortName.offsetByCodePoints(end, -1)) + "…";
        }
        Component label = text(shortName, TEXT);
        if (team.getPlayers().isEmpty()) return label;
        var id = team.getPlayers().getFirst(); var player = Bukkit.getOfflinePlayer(id);
        return Component.textOfChildren(StringUtil.resolvedHead(id, player.getName(), player.getPlayerProfile().getProperties()), Component.space(), label);
    }
    private Component side(me.advait.contender.duel.DuelTeam team) {
        Component text = Component.empty();
        for (var id : team.getPlayers()) {
            if (!text.equals(Component.empty())) text = text.append(DialogText.muted(" / "));
            var player = Bukkit.getOfflinePlayer(id);
            text = text.append(me.advait.contender.util.StringUtil.resolvedHead(id, player.getName(), player.getPlayerProfile().getProperties()))
                    .append(Component.space()).append(plugin.getNameTagManager().displayName(id, player.getName() == null ? "Player" : player.getName()));
        }
        return text;
    }
}
