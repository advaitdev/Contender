package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.tab.TabStyle;
import me.advait.contender.tournament.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static me.advait.contender.dialog.DialogPalette.*;

/** Category menus have one column. Every setup form ends with Back / Next at the same width. */
public final class TournamentDialogs {
    private static final int CONTENT_WIDTH = 320;
    private static final int INPUT_WIDTH = 300;
    private static final int NAV_WIDTH = 150;
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, UUID> rosterViews = new HashMap<>();
    private record Draft(String name, String map, String kit, boolean teams, boolean waitForRound, int bestOf,
                         String players, int delay, int parallel, int bracketRounds) { }
    public TournamentDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }

    private ActionButton action(Player owner, DialogIcon icon, String label, TextColor color, int width,
                                String hint, BiConsumer<Player, DialogResponseView> callback) {
        return dialogs.button(owner, icon.label(label, color), DialogText.muted(hint), true, width, callback);
    }
    private ActionButton menuAction(Player owner, DialogIcon icon, String label, TextColor color,
                                    String hint, Consumer<Player> callback) {
        return action(owner, icon, label, color, INPUT_WIDTH, hint, (p, response) -> callback.accept(p));
    }
    private ActionButton nav(Player owner, DialogIcon icon, String label, TextColor color,
                             String hint, BiConsumer<Player, DialogResponseView> callback) {
        return action(owner, icon, label, color, NAV_WIDTH, hint, callback);
    }
    private DialogBody body(Component text) { return DialogBody.plainMessage(text, CONTENT_WIDTH); }
    private Component section(DialogIcon icon, String title, Component contents) {
        return DialogText.lines(icon.label(title, ACCENT), contents);
    }
    private void menu(Player player, String title, Component contents, List<ActionButton> buttons, Consumer<Player> back) {
        ActionButton footer = back == null ? null : nav(player, DialogIcon.BACK, "Back", MUTED,
                "Return to the previous menu.", (p, response) -> back.accept(p));
        dialogs.show(player, title, List.of(body(contents)), List.of(), buttons, 1, NAV_WIDTH, footer);
    }
    private Component progress(int step) {
        String[] labels = {"Details", "Players", "Rules", "Review"};
        Component line = Component.empty();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) line = line.append(text("  ›  ", MUTED));
            line = line.append(text(labels[i], i == step - 1 ? ACCENT : MUTED));
        }
        return line;
    }
    private void form(Player player, int step, List<DialogBody> contents, List<DialogInput> inputs,
                      ActionButton back, ActionButton next) {
        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(body(progress(step).append(Component.newline())));
        bodies.addAll(contents);
        List<ActionButton> actions = new ArrayList<>();
        Dialogs.navigationRow(actions, back, next);
        dialogs.show(player, step == 4 ? "Review Tournament" : "New Tournament", bodies, inputs, actions, 2, NAV_WIDTH, null);
    }

    public void open(Player player) {
        rosterViews.remove(player.getUniqueId());
        if (plugin.getMinigameManager() != null) {
            var mode = plugin.getMinigameManager().selected();
            if (mode != null) { mode.open(player); return; }
        } else if (plugin.getRaceManager() != null && plugin.getRaceManager().displayed() != null) { new RaceDialogs(plugin).open(player); return; }
        var manager = plugin.getTournamentManager();
        Tournament tournament = manager.current();
        boolean canCreate = tournament == null || tournament.isComplete() || tournament.isCancelled();
        List<ActionButton> buttons = new ArrayList<>();
        // The main action always comes first, including after a completed or cancelled stage.
        if (canCreate) buttons.add(menuAction(player, DialogIcon.TOURNAMENT, "New Tournament", ACCENT,
                "Choose a map, enter the roster, and set up the matches.", this::formats));
        else buttons.add(menuAction(player, tournament.isRunning() ? DialogIcon.PAUSE : DialogIcon.NEXT,
                tournament.isRunning() ? "Pause Tournament" : "Start Tournament", ACCENT,
                tournament.isRunning() ? "Let active matches finish and pause new ones." : "Start available matches from this bracket.", p -> {
                    current(tournament.id());
                    if (tournament.isRunning()) manager.pause(); else manager.resume();
                    open(p);
                }));
        Component contents = DialogText.paragraphs(text("No tournament selected", TEXT),
                DialogText.muted("Choose who plays and how many bracket rounds to run."));
        if (tournament != null) {
            var map = plugin.getMapManager().getMap(tournament.mapId());
            var kit = plugin.getKitManager().getKit(tournament.kitId());
            long finished = tournament.matches().stream().filter(m -> m.status() == TournamentMatch.Status.FINISHED).count();
            contents = DialogText.paragraphs(text(tournament.name(), TEXT), DialogText.lines(
                    DialogText.detail("Status", tournament.statusText(), tournament.isCancelled() ? DANGER : tournament.isRunning() || tournament.isComplete() ? SUCCESS : MUTED),
                    DialogText.detail("Matches", finished + " / " + tournament.matches().size() + " finished")), DialogText.lines(
                    DialogText.detail("Map", map == null ? tournament.mapId() : map.getDisplayName()),
                    DialogText.detail("Kit", kit == null ? tournament.kitId() : kit.getDisplayName())));
            if (tournament.isRunning() && !manager.waitingReason().isBlank()) contents = DialogText.paragraphs(contents, DialogText.muted(manager.waitingReason()));
            buttons.add(menuAction(player, DialogIcon.DUEL, canCreate ? "Match Results" : "Matches", TEXT,
                    "Scores, match settings, and spectating.", p -> matches(p, tournament.id(), 0)));
            buttons.add(menuAction(player, DialogIcon.BOARD, "Bracket & Standings", TEXT,
                    "Choose the round or standings shown in your tab list.", p -> new BracketDialogs(plugin).open(p)));
        }
        buttons.add(menuAction(player, DialogIcon.SETTINGS, "Setup Tools", TEXT,
                "Maps, kits, the tournament board, and the lobby.", this::tools));
        if (!canCreate) buttons.add(menuAction(player, DialogIcon.CLOSE, "Cancel Tournament", DANGER,
                "Stop the tournament and end its active matches.", p -> confirmCancel(p, tournament.id())));
        menu(player, "Tournament", contents, buttons, null);
    }
    public void tools(Player player) {
        menu(player, "Setup Tools", DialogText.muted("Prepare the server before you start a tournament."), List.of(
                menuAction(player, DialogIcon.MACE, "Minigames", TEXT, "Prepare race courses, the End world, and Combo arenas.", this::minigameTools),
                menuAction(player, DialogIcon.MAP, "Maps & Kits", TEXT, "Create maps and edit the kits used in matches.", this::assets),
                menuAction(player, DialogIcon.BOARD, "Board & Lobby", TEXT, "Place the board and set the lobby return point.", this::venue),
                menuAction(player, DialogIcon.NAME, "Tab Title", TEXT, "Change the title shown above tab and the board.", p -> new SettingsDialogs(plugin).tab(p, this::tools)),
                menuAction(player, DialogIcon.CLOSE, "Force Cancel All", DANGER, "Stop all events, including interrupted games.", this::confirmCancelAll)), this::open);
    }
    private void confirmCancelAll(Player player) {
        menu(player, "Force Cancel All?", DialogText.muted("Stop every tournament, minigame, duel, and vote.\nPlayers will be returned to the lobby.\n\nUse this if an interrupted event is stuck."), List.of(
                menuAction(player, DialogIcon.CLOSE, "Force Cancel All", DANGER, "Stop all events now.", p -> {
                    p.closeDialog();
                    new me.advait.contender.command.CancelAllCommand(plugin).cancel(p);
                })), this::tools);
    }
    private void minigameTools(Player player) {
        menu(player, "Minigames", DialogText.muted("Prepare each mode before creating the event."), List.of(
                menuAction(player, DialogIcon.MACE, "Race Courses", TEXT, "Set up checkpoints and race rules.", p -> new RaceDialogs(plugin).courses(p, 0)),
                menuAction(player, DialogIcon.DUEL, "Race Kits", TEXT, "Edit the default race loadout or a saved kit.", p -> new RaceDialogs(plugin).kits(p)),
                menuAction(player, DialogIcon.CRYSTAL, "Manhunt World", TEXT, "Prepare an End world and its pillar spawns.", p -> plugin.getManhuntManager().setupDialog(p)),
                menuAction(player, DialogIcon.AXE, "Combo Arenas", TEXT, "Set the player and bot spawns in a saved map.", p -> plugin.getComboManager().setupDialog(p))), this::tools);
    }
    private void assets(Player player) {
        menu(player, "Maps & Kits", DialogText.muted("Maps need both team spawns. Kits need to be saved before they can be selected."), List.of(
                menuAction(player, DialogIcon.MAP, "Maps", TEXT, "Selections, spawns, and arena copies.", p -> new ArenaDialogs(plugin).open(p)),
                menuAction(player, DialogIcon.DUEL, "Edit Kits", TEXT, "Create a kit or change its items and rules.", this::editKits)), this::tools);
    }
    private void venue(Player player) {
        var manager = plugin.getTournamentManager();
        menu(player, "Board & Lobby", DialogText.muted("Placement uses your current position and the direction you are facing."), List.of(
                menuAction(player, DialogIcon.BOARD, "Place Board Here", TEXT, "Place or move the tournament board in front of you.", p -> {
                    manager.board().place(p); manager.board().update(manager.current(), manager.playing());
                    p.closeDialog(); Dialogs.tell(p, "Tournament board placed in front of you.");
                }),
                menuAction(player, DialogIcon.SPAWN, "Set Lobby Here", TEXT, "Players return to this position after their matches.", p -> {
                    plugin.getLobbyManager().setLobbyLocation(p.getLocation()); Dialogs.tell(p, "Lobby set here."); venue(p);
                }),
                menuAction(player, DialogIcon.REFRESH, "Place Vote Timer Here", TEXT, "Show a large timer here during votes.", p -> { plugin.getVoteManager().timer().place(p); p.closeDialog(); Dialogs.tell(p, "Timer placed. It appears during votes."); }),
                menuAction(player, DialogIcon.CLOSE, "Remove Vote Timer", DANGER, "Remove the timer location.", p -> { plugin.getVoteManager().timer().remove(); venue(p); }),
                menuAction(player, DialogIcon.BOARD, "Board Appearance", TEXT, "Change the board background opacity.", p -> new SettingsDialogs(plugin).board(p, this::venue)),
                menuAction(player, DialogIcon.CLOSE, "Remove Board", DANGER, "Remove the board while keeping the tournament.", p -> { manager.board().remove(); venue(p); })), this::tools);
    }
    private Tournament current(UUID id) {
        Tournament tournament = plugin.getTournamentManager().current();
        if (tournament == null || !tournament.id().equals(id)) throw new IllegalStateException("That tournament is no longer selected.");
        return tournament;
    }
    public void formats(Player player) {
        menu(player, "Tournament Format", DialogText.muted("Choose how this stage will run."), List.of(
                menuAction(player, DialogIcon.DUEL, "Round Robin", ACCENT, "Schedule duels and rank players by their results.", this::create),
                menuAction(player, DialogIcon.MACE, "Mace Race", TEXT, "Race through checkpoints and rank players by finish time.", p -> new RaceDialogs(plugin).create(p)),
                menuAction(player, DialogIcon.CRYSTAL, "Manhunt", TEXT, "Runners face hunters in the End. Both teams have one life.", p -> plugin.getManhuntManager().createDialog(p)),
                menuAction(player, DialogIcon.AXE, "Combo", TEXT, "Take turns against a sword bot and rank each player's longest combo.", p -> plugin.getComboManager().createDialog(p))), this::open);
    }
    private void create(Player player) { create(player, null); }
    private void editKits(Player player) {
        player.closeDialog();
        new KitDialogs(plugin).open(player, this::open);
    }
    private void create(Player player, Draft initial) {
        rosterViews.remove(player.getUniqueId());
        var maps = plugin.getMapManager().getMaps().stream().filter(m -> m.isComplete()).toList();
        var kits = new ArrayList<>(plugin.getKitManager().getKits());
        var requirements = new SetupRequirements(maps.size(), kits.size());
        if (!requirements.ready()) {
            menu(player, "Before You Begin", requirements.body(), List.of(
                    menuAction(player, DialogIcon.REFRESH, "Check Again", ACCENT, "Continue once a map and kit are ready.", p -> create(p, initial)),
                    menuAction(player, DialogIcon.MAP, "Maps", TEXT, "Save a map and set both team spawns.", p -> new ArenaDialogs(plugin).open(p)),
                    menuAction(player, DialogIcon.DUEL, "Edit Kits", TEXT, "Create and save a kit.", this::editKits)), this::open);
            return;
        }
        Draft draft = initial == null ? new Draft("Tournament", maps.getFirst().getId(), kits.getFirst().getId(), false, false, 3,
                String.join(", ", Bukkit.getOnlinePlayers().stream().filter(p -> plugin.getDuelManager().isEligible(p.getUniqueId())).map(Player::getName).toList()), 10, 20, 0) : initial;
        List<DialogInput> inputs = List.of(
                DialogInput.text("name", DialogIcon.NAME.label("Tournament Name")).initial(draft.name()).maxLength(64).width(INPUT_WIDTH).build(),
                DialogInput.singleOption("map", DialogIcon.MAP.label("Map"), maps.stream().map(m -> Dialogs.option(m.getId(), m.getDisplayName(), m.getId().equals(draft.map()))).toList()).width(INPUT_WIDTH).build(),
                DialogInput.singleOption("kit", DialogIcon.DUEL.label("Kit"), kits.stream().map(k ->
                        io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create(k.getId(), KitIcons.label(k), k.getId().equals(draft.kit()))).toList()).width(INPUT_WIDTH).build());
        form(player, 1, List.of(body(DialogText.muted("Choose a name, map, and kit for this stage.\nYou will see the title preview before creating it."))), inputs,
                nav(player, DialogIcon.BACK, "Back", MUTED, "Return to the tournament menu.", (p, view) -> open(p)),
                nav(player, DialogIcon.NEXT, "Next: Players", ACCENT, "Choose who will play.", (p, view) -> roster(p, details(view, draft))));
    }
    private Draft details(DialogResponseView view, Draft previous) {
        String name = Dialogs.text(view, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Give this tournament a name.");
        return new Draft(name, Dialogs.text(view, "map"), Dialogs.text(view, "kit"), previous.teams(), previous.waitForRound(),
                previous.bestOf(), previous.players(), previous.delay(), previous.parallel(), previous.bracketRounds());
    }
    private void roster(Player player, Draft draft) { roster(player, draft, draft.players(), draft.delay(), draft.parallel(), null); }
    private void roster(Player player, Draft draft, String initial, int delay, int parallel, String error) {
        rosterViews.remove(player.getUniqueId());
        List<DialogBody> contents = new ArrayList<>();
        if (error != null) contents.add(body(text(error, DANGER)));
        contents.add(body(DialogText.lines(DialogText.detail("Players", "Alice, Bob, Charlie"),
                DialogText.detail("Teams", "One team per line, e.g. Red: Alice, Bob"))));
        contents.add(body(DialogText.muted("Offline contestants can be entered too.\nOnly contestants can play.")));
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("roster", DialogIcon.PLAYERS.label("Roster Type"), List.of(
                        Dialogs.option("solo", "Players (1v1)", !draft.teams()), Dialogs.option("teams", "Teams", draft.teams()))).width(INPUT_WIDTH).build(),
                DialogInput.text("players", DialogIcon.NAME.label("Roster")).initial(initial).maxLength(4096)
                        .multiline(TextDialogInput.MultilineOptions.create(64, 100)).width(INPUT_WIDTH).build());
        form(player, 2, contents, inputs,
                nav(player, DialogIcon.BACK, "Back", MUTED, "Change the name, map, or kit. Your roster is kept.", (p, view) -> create(p, entries(view, draft, delay, parallel))),
                nav(player, DialogIcon.NEXT, "Next: Rules", ACCENT, "Set the match length and schedule.", (p, view) -> {
                    Draft next = entries(view, draft, delay, parallel);
                    if (next.players().isBlank()) throw new IllegalArgumentException("Enter the players or teams first.");
                    rules(p, next);
                }));
    }
    private Draft entries(DialogResponseView view, Draft previous, int delay, int parallel) {
        return new Draft(previous.name(), previous.map(), previous.kit(), Dialogs.text(view, "roster").equals("teams"),
                previous.waitForRound(), previous.bestOf(), Dialogs.text(view, "players"), delay, parallel, previous.bracketRounds());
    }
    private Draft rules(DialogResponseView view, Draft previous) {
        int full = RoundRobinSchedule.fullRounds(RosterParser.entryCount(previous.players(), previous.teams()));
        return new Draft(previous.name(), previous.map(), previous.kit(), previous.teams(), Dialogs.text(view, "schedule").equals("rounds"),
                Dialogs.number(view, "wins", 1, 8) * 2 - 1, previous.players(), Dialogs.number(view, "delay", 5, 60), Dialogs.number(view, "parallel", 1, 100),
                full == 1 ? 1 : Dialogs.number(view, "bracket_rounds", 1, full));
    }
    private void rules(Player player, Draft draft) {
        int count = RosterParser.entryCount(draft.players(), draft.teams()), full = RoundRobinSchedule.fullRounds(count);
        List<DialogInput> inputs = new ArrayList<>();
        if (full > 1) inputs.add(DialogInput.numberRange("bracket_rounds", DialogIcon.BOARD.label("Bracket Rounds"), 1, full)
                .initial((float) (draft.bracketRounds() == 0 ? full : Math.min(draft.bracketRounds(), full))).step(1f).width(INPUT_WIDTH).build());
        inputs.addAll(List.of(
                DialogInput.numberRange("wins", DialogIcon.DUEL.label("Round Wins Needed"), 1, 8).initial((float) (draft.bestOf() / 2 + 1)).step(1f).width(INPUT_WIDTH).build(),
                DialogInput.numberRange("delay", DialogIcon.REFRESH.label("Sorting Time (seconds)"), 5, 60).initial((float) draft.delay()).step(1f).width(INPUT_WIDTH).build(),
                DialogInput.numberRange("parallel", DialogIcon.TOURNAMENT.label("Matches at Once"), 1, 100).initial((float) draft.parallel()).step(1f).width(INPUT_WIDTH).build(),
                DialogInput.singleOption("schedule", DialogIcon.BOARD.label("Schedule"), List.of(
                        Dialogs.option("free", "As arenas become free", !draft.waitForRound()),
                        Dialogs.option("rounds", "Finish each bracket round", draft.waitForRound()))).width(INPUT_WIDTH).build()));
        Component schedule = DialogText.detail("Full schedule", full + (full == 1 ? " bracket round" : " bracket rounds")
                + " · " + count * (count - 1) / 2 + " matches");
        schedule = DialogText.paragraphs(schedule, DialogText.muted("Each bracket round schedules at most one match per entry.\nRound Wins Needed sets the length of each match."));
        if (count % 2 != 0) schedule = DialogText.paragraphs(schedule, DialogText.muted("An odd roster has one bye per bracket round.\nSome entries may play one fewer match."));
        form(player, 3, List.of(body(schedule)), inputs,
                nav(player, DialogIcon.BACK, "Back", MUTED, "Edit the roster. Your match settings are kept.", (p, view) -> roster(p, rules(view, draft))),
                nav(player, DialogIcon.NEXT, "Next: Review", ACCENT, "Check the title, roster, and match rules.", (p, view) -> review(p, rules(view, draft))));
    }
    private Component rosterSummary(Draft draft) {
        String[] entries = Arrays.stream(draft.players().split(draft.teams() ? "\\R" : "[,\\s]+"))
                .map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
        int shown = Math.min(entries.length, draft.teams() ? 4 : 8);
        Component summary = text(String.join(draft.teams() ? "\n" : ", ", Arrays.copyOf(entries, shown)), TEXT);
        if (entries.length > shown) summary = summary.append(Component.newline()).append(DialogText.muted(
                "+ " + (entries.length - shown) + " more " + (draft.teams() ? "teams" : "players") + " in the roster"));
        return summary;
    }
    private void review(Player player, Draft draft) {
        UUID form = UUID.randomUUID(); rosterViews.put(player.getUniqueId(), form);
        var map = plugin.getMapManager().getMap(draft.map()); var kit = plugin.getKitManager().getKit(draft.kit());
        int count = RosterParser.entryCount(draft.players(), draft.teams());
        List<DialogBody> contents = List.of(
                body(section(DialogIcon.PREVIEW, "Title Preview", TabStyle.read(plugin.getConfig()).header(draft.name()))),
                body(section(DialogIcon.MAP, "Map & Kit", DialogText.lines(
                        DialogText.detail("Map", map == null ? draft.map() : map.getDisplayName()),
                        DialogText.detail("Kit", kit == null ? draft.kit() : kit.getDisplayName())))),
                body(section(DialogIcon.PLAYERS, draft.teams() ? "Teams" : "Players", rosterSummary(draft))),
                body(section(DialogIcon.DUEL, "Match Rules", DialogText.lines(
                        DialogText.detail("Bracket rounds", draft.bracketRounds() + " of " + RoundRobinSchedule.fullRounds(count)),
                        DialogText.detail("Matches", Integer.toString(count / 2 * draft.bracketRounds())),
                        DialogText.detail("Win condition", "First to " + (draft.bestOf() / 2 + 1) + " round wins"),
                        DialogText.detail("Sorting", draft.delay() + " seconds"), DialogText.detail("Matches at once", Integer.toString(draft.parallel())),
                        DialogText.detail("Schedule", draft.waitForRound() ? "Finish each bracket round" : "As arenas become free")))),
                body(DialogText.muted("Create saves the bracket. Start it from the tournament menu when you are ready.")));
        form(player, 4, contents, List.of(),
                nav(player, DialogIcon.BACK, "Back", MUTED, "Make changes before creating the tournament.", (p, view) -> { rosterViews.remove(p.getUniqueId()); rules(p, draft); }),
                nav(player, DialogIcon.SAVE, "Create Tournament", ACCENT, "Save the bracket without starting matches.", (p, view) -> {
                    if (form.equals(rosterViews.get(p.getUniqueId()))) lookupRoster(p, form, draft, draft.players(), draft.delay(), draft.parallel());
                }));
    }
    private void lookupRoster(Player player, UUID form, Draft draft, String names, int delay, int parallel) {
        var result = RosterParser.parseAsync(names, draft.teams(), PlayerIdentityResolver::resolve);
        UUID owner = player.getUniqueId(); UUID request = UUID.randomUUID();
        if (!rosterViews.replace(owner, form, request)) return;
        Tournament previous = plugin.getTournamentManager().current();
        menu(player, "Checking Players", DialogText.muted("Checking the roster before saving your bracket."), List.of(
                menuAction(player, DialogIcon.BACK, "Back to Roster", MUTED, "Stop the lookup and edit the roster.", p -> {
                    if (rosterViews.remove(owner, request)) roster(p, draft, names, delay, parallel, null);
                })), null);
        result.whenComplete((entries, failure) -> {
            if (!plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!rosterViews.remove(owner, request) || !plugin.isEnabled() || !player.isOnline()) return;
                    if (!player.hasPermission("contender.master")) {
                        player.closeDialog(); Dialogs.error(player, "You don't have permission to create a tournament."); return;
                    }
                    if (failure != null) { roster(player, draft, names, delay, parallel, Dialogs.message(failure)); return; }
                    try {
                        if (plugin.getTournamentManager().current() != previous) throw new IllegalStateException("The selected tournament changed. Open /tournament to check it.");
                        Tournament tournament = new Tournament(UUID.randomUUID(), draft.name(), draft.map(), draft.kit(), entries,
                                draft.teams(), draft.waitForRound(), draft.bestOf(), delay, parallel, draft.bracketRounds());
                        plugin.getTournamentManager().create(tournament);
                        open(player); Dialogs.tell(player, "Tournament saved. Start it when you're ready.");
                    } catch (Exception error) { roster(player, draft, names, delay, parallel, Dialogs.message(error)); }
                });
            } catch (IllegalPluginAccessException ignored) {
                // The plugin stopped before the main-thread callback could be scheduled.
            }
        });
    }
    private void confirmCancel(Player player, UUID id) {
        menu(player, "Cancel Tournament?", DialogText.paragraphs(text(current(id).name(), TEXT),
                DialogText.muted("Active matches will end.\nThe remaining matches will not be played.")), List.of(
                menuAction(player, DialogIcon.CLOSE, "Cancel Tournament", DANGER, "End this tournament.", p -> { current(id); plugin.getTournamentManager().cancel(); open(p); })), this::open);
    }
    private void matches(Player player, UUID id, int page) {
        Tournament tournament = current(id);
        List<ActionButton> buttons = new ArrayList<>();
        int pages = Math.max(1, (tournament.matches().size() + 7) / 8);
        page = Math.clamp(page, 0, pages - 1); int selectedPage = page;
        for (int i = page * 8; i < Math.min(page * 8 + 8, tournament.matches().size()); i++) {
            TournamentMatch match = tournament.matches().get(i);
            String label = "#" + match.number() + "  " + tournament.entries().get(match.first()).name() + " vs " + tournament.entries().get(match.second()).name();
            buttons.add(action(player, DialogIcon.DUEL, label, TEXT, 220, "View this match and its settings.",
                    (p, view) -> match(p, id, match.number(), selectedPage)));
        }
        // Match menus use two columns so paging remains on its own paired row.
        Dialogs.navigationRow(buttons,
                page > 0 ? action(player, DialogIcon.BACK, "Previous Page", MUTED, 220, "View earlier matches.", (p, view) -> matches(p, id, selectedPage - 1)) : null,
                page + 1 < pages ? action(player, DialogIcon.NEXT, "Next Page", ACCENT, 220, "View later matches.", (p, view) -> matches(p, id, selectedPage + 1)) : null);
        dialogs.show(player, "Matches", List.of(body(text(tournament.name(), TEXT)), body(DialogText.page(page + 1, pages))), List.of(), buttons, 2, NAV_WIDTH,
                nav(player, DialogIcon.BACK, "Back", MUTED, "Return to the tournament menu.", (p, response) -> open(p)));
    }
    private void match(Player player, UUID id, int number, int page) {
        Tournament tournament = current(id); TournamentMatch match = tournament.matches().get(number - 1);
        String first = tournament.entries().get(match.first()).name(), second = tournament.entries().get(match.second()).name();
        List<ActionButton> buttons = new ArrayList<>(); List<DialogInput> inputs = new ArrayList<>();
        Component contents = DialogText.paragraphs(text(first + " vs " + second, TEXT), DialogText.lines(
                DialogText.detail("Bracket round", Integer.toString(match.round())),
                DialogText.detail("Win condition", "First to " + (match.bestOf() / 2 + 1) + " round wins")));
        if (match.result() != null) contents = DialogText.paragraphs(contents,
                DialogText.detail("Final score", match.result().team1Score() + " - " + match.result().team2Score()));
        else contents = DialogText.paragraphs(contents, DialogText.detail("Status", tournament.isCancelled() ? "Cancelled"
                : match.status() == TournamentMatch.Status.PLAYING ? "Playing" : "Waiting", tournament.isCancelled() ? DANGER : MUTED));
        if (match.status() == TournamentMatch.Status.WAITING && !tournament.isCancelled()) {
            inputs.add(DialogInput.numberRange("wins", DialogIcon.DUEL.label("Round Wins Needed"), 1, 8)
                    .initial((float) (match.bestOf() / 2 + 1)).step(1f).width(INPUT_WIDTH).build());
            buttons.add(action(player, DialogIcon.SAVE, "Save Win Condition", ACCENT, INPUT_WIDTH, "Apply this to the waiting match.", (p, view) -> {
                current(id); plugin.getTournamentManager().setBestOf(match, Dialogs.number(view, "wins", 1, 8) * 2 - 1); match(p, id, number, page);
            }));
            buttons.add(menuAction(player, DialogIcon.PLAYERS, "Award a Win", TEXT, "Choose the winner of a forfeit.", p -> award(p, id, match, page)));
        }
        var duel = plugin.getTournamentManager().playing().get(number);
        if (duel != null && !tournament.isCancelled()) buttons.add(menuAction(player, DialogIcon.PREVIEW, "Spectate Match", ACCENT,
                "Join this match as a spectator.", p -> { current(id); plugin.getDuelManager().spectate(p, duel); p.closeDialog(); }));
        dialogs.show(player, "Match #" + number, List.of(body(contents)), inputs, buttons, 1, NAV_WIDTH,
                nav(player, DialogIcon.BACK, "Back", MUTED, "Return to the match list.", (p, response) -> matches(p, id, page)));
    }
    private void award(Player player, UUID id, TournamentMatch match, int page) {
        Tournament tournament = current(id);
        String first = tournament.entries().get(match.first()).name(), second = tournament.entries().get(match.second()).name();
        menu(player, "Award a Win", DialogText.muted("Choose the winner.\nThe match will be recorded as a forfeit."), List.of(
                menuAction(player, DialogIcon.PLAYERS, first, TEXT, "Award the match to " + first + ".", p -> confirmAward(p, id, match, 1, page)),
                menuAction(player, DialogIcon.PLAYERS, second, TEXT, "Award the match to " + second + ".", p -> confirmAward(p, id, match, 2, page))), p -> match(p, id, match.number(), page));
    }
    private void confirmAward(Player player, UUID id, TournamentMatch match, int side, int page) {
        Tournament tournament = current(id); String name = tournament.entries().get(side == 1 ? match.first() : match.second()).name();
        menu(player, "Confirm Forfeit", DialogText.lines(DialogText.detail("Winner", name),
                DialogText.detail("Match", "#" + match.number())), List.of(menuAction(player, DialogIcon.SAVE, "Award Win", ACCENT,
                "Record this match as a forfeit.", p -> { current(id); plugin.getTournamentManager().award(match, side); matches(p, id, page); })),
                p -> award(p, id, match, page));
    }
}
