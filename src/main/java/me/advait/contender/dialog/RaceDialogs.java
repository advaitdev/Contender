package me.advait.contender.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import me.advait.contender.gui.duel.KitEditorGUI;
import me.advait.contender.kit.Kit;
import me.advait.contender.Contender;
import me.advait.contender.race.*;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.tab.TabStyle;
import me.advait.contender.tournament.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.*;
import static me.advait.contender.dialog.DialogPalette.*;

public final class RaceDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, UUID> lookups = new HashMap<>();
    private static final String BUILT_IN_KIT = "@default";
    private record Draft(String title, String map, String roster, int advance, int minutes, String kitId) { }
    public RaceDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    private ActionButton button(Player player, DialogIcon icon, String label, TextColor color, int width, BiConsumer<Player, DialogResponseView> action) {
        return dialogs.button(player, icon.label(label, color), null, true, width, action);
    }
    private ActionButton action(Player player, DialogIcon icon, String label, TextColor color, Consumer<Player> action) {
        return button(player, icon, label, color, 300, (p, view) -> action.accept(p));
    }
    private void menu(Player player, String title, Component body, List<ActionButton> actions, Consumer<Player> back) {
        lookups.remove(player.getUniqueId());
        dialogs.show(player, title, List.of(DialogBody.plainMessage(body, 320)), List.of(), actions, 1, 150,
                back == null ? null : button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> back.accept(p)));
    }
    private void form(Player player, String title, Component body, List<DialogInput> inputs, ActionButton back, ActionButton next) {
        lookups.remove(player.getUniqueId());
        List<ActionButton> actions = new ArrayList<>(); Dialogs.navigationRow(actions, back, next, 150);
        dialogs.show(player, title, List.of(DialogBody.plainMessage(body, 320)), inputs, actions, 2, 150, null);
    }
    public void open(Player player) {
        RaceRun race = plugin.getRaceManager().displayed();
        if (race == null) { new TournamentDialogs(plugin).open(player); return; }
        List<ActionButton> actions = new ArrayList<>();
        if (race.terminal()) actions.add(action(player, DialogIcon.TOURNAMENT, "New Tournament", ACCENT, p -> new TournamentDialogs(plugin).formats(p)));
        else if (!plugin.getRaceManager().active()) actions.add(action(player, DialogIcon.NEXT, "Start Race", ACCENT, p -> { plugin.getRaceManager().start(race.id()); open(p); }));
        actions.add(action(player, DialogIcon.BOARD, "Leaderboard", TEXT, p -> standings(p, race.id(), 0)));
        if (!race.terminal()) actions.add(action(player, DialogIcon.PLAYERS, "Manage Racers", TEXT, p -> racers(p, race.id(), 0)));
        actions.add(action(player, DialogIcon.SETTINGS, "Setup Tools", TEXT, p -> new TournamentDialogs(plugin).tools(p)));
        if (plugin.getRaceManager().active()) actions.add(action(player, DialogIcon.SAVE, "End Race", TEXT, p -> confirmEnd(p, race.id(), false)));
        if (!race.terminal()) actions.add(action(player, DialogIcon.CLOSE, "Cancel Race", DANGER, p -> confirmEnd(p, race.id(), true)));
        menu(player, "Mace Race", DialogText.paragraphs(TabStyle.read(plugin.getConfig()).header(race.name()), DialogText.lines(
                DialogText.detail("Status", race.statusText()), DialogText.detail("Racers", String.valueOf(race.racers().size())),
                DialogText.detail("Course", mapName(race.mapId())), kitDetail(race.kitId()), DialogText.detail("Checkpoint Jump", "Up to " + race.maxAdvance() + " ahead"))), actions, null);
    }
    private void confirmEnd(Player player, UUID id, boolean cancel) {
        plugin.getRaceManager().requireCurrent(id);
        menu(player, cancel ? "Cancel Race" : "End Race", DialogText.muted(cancel
                        ? "Return everyone to the lobby and clear the race from tab."
                        : "Keep the finish times. Racers still on the course will be marked Did Not Finish."), List.of(
                action(player, cancel ? DialogIcon.CLOSE : DialogIcon.SAVE, cancel ? "Cancel Race" : "End Race", cancel ? DANGER : ACCENT, p -> {
                    plugin.getRaceManager().requireCurrent(id); plugin.getRaceManager().end(cancel ? RaceRun.State.CANCELLED : RaceRun.State.FINISHED); open(p);
                })), this::open);
    }
    public void create(Player player) {
        var courses = plugin.getRaceManager().courses().stream().filter(c -> plugin.getMapManager().getMap(c.mapId()) != null).toList();
        if (courses.isEmpty()) {
            menu(player, "Set Up a Course", DialogText.muted("Save a map selection, add checkpoint mobs, and set the start.\nThen save it as a race course."), List.of(
                    action(player, DialogIcon.MAP, "Race Courses", ACCENT, p -> courses(p, 0)),
                    action(player, DialogIcon.MAP, "Maps", TEXT, p -> new ArenaDialogs(plugin).open(p))), p -> new TournamentDialogs(plugin).formats(p));
            return;
        }
        var course = courses.getFirst();
        details(player, new Draft("Mace Race", course.mapId(), String.join(", ", Bukkit.getOnlinePlayers().stream()
                .filter(p -> plugin.getRoleManager().isContestant(p.getUniqueId())).map(Player::getName).toList()), course.maxAdvance(), 0, ""));
    }
    private void details(Player player, Draft draft) {
        var choices = plugin.getRaceManager().courses().stream().filter(c -> plugin.getMapManager().getMap(c.mapId()) != null).map(c -> Dialogs.option(c.mapId(), mapName(c.mapId()), draft.map().equals(c.mapId()))).toList();
        form(player, "Mace Race · Details", DialogText.muted("Everyone races on the same course.\nThe fastest finish time wins."), List.of(
                DialogInput.text("name", DialogIcon.NAME.label("Tournament Name")).initial(draft.title()).maxLength(64).width(300).build(),
                DialogInput.singleOption("course", DialogIcon.MAP.label("Course"), choices).width(300).build(),
                DialogInput.singleOption("kit", DialogIcon.MACE.label("Kit"), kitOptions(draft.kitId())).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> new TournamentDialogs(plugin).formats(p)),
                button(player, DialogIcon.NEXT, "Next: Players", ACCENT, 150, (p, v) -> {
                    String map = Dialogs.text(v, "course"), name = Dialogs.text(v, "name");
                    if (name.isBlank()) throw new IllegalArgumentException("Give the race a name.");
                    roster(p, new Draft(name, map, draft.roster(), map.equals(draft.map()) ? draft.advance() : plugin.getRaceManager().course(map).maxAdvance(), draft.minutes(), readKit(v)));
                }));
    }
    private void roster(Player player, Draft draft) {
        form(player, "Mace Race · Players", DialogText.muted("Enter contestant names, separated by commas.\nOffline players can be entered before the event."), List.of(
                DialogInput.text("players", DialogIcon.PLAYERS.label("Racers")).initial(draft.roster()).maxLength(2048).width(300)
                        .multiline(TextDialogInput.MultilineOptions.create(64, 100)).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> details(p, new Draft(draft.title(), draft.map(), Dialogs.text(v, "players"), draft.advance(), draft.minutes(), draft.kitId()))),
                button(player, DialogIcon.NEXT, "Next: Rules", ACCENT, 150, (p, v) -> rules(p, new Draft(draft.title(), draft.map(), Dialogs.text(v, "players"), draft.advance(), draft.minutes(), draft.kitId()))));
    }
    private void rules(Player player, Draft draft) {
        form(player, "Mace Race · Rules", DialogText.paragraphs(
                DialogText.muted("A jump of 15 allows #1 → #16.\nThe finish mob counts as the next checkpoint after the last number.\n\nA time limit of 0 lets you end the race manually."),
                groundReturnDetail(draft.map())), List.of(
                DialogInput.text("advance", DialogIcon.STEP.label("Maximum Checkpoint Jump")).initial(String.valueOf(draft.advance())).maxLength(4).width(300).build(),
                DialogInput.text("minutes", DialogIcon.REFRESH.label("Time Limit (minutes)")).initial(String.valueOf(draft.minutes())).maxLength(4).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> roster(p, readRules(v, draft))),
                button(player, DialogIcon.NEXT, "Next: Review", ACCENT, 150, (p, v) -> resolve(p, readRules(v, draft))));
    }
    private Draft readRules(DialogResponseView view, Draft draft) {
        return new Draft(draft.title(), draft.map(), draft.roster(), integer(view, "advance", 1, 1000), integer(view, "minutes", 0, 1440), draft.kitId());
    }
    private void resolve(Player player, Draft draft) {
        // A single racer is useful for testing a course; larger rosters share the tournament resolver.
        String[] names = draft.roster().strip().split("[,\\s]+");
        java.util.concurrent.CompletableFuture<List<TournamentEntry>> future;
        if (names.length == 1 && !names[0].isBlank()) future = PlayerIdentityResolver.resolve(names[0]).thenApply(identity -> List.of(new TournamentEntry(identity.name(), List.of(identity.id()), Map.of(identity.id(), identity.name()))));
        else future = RosterParser.parseAsync(draft.roster(), false, PlayerIdentityResolver::resolve);
        menu(player, "Finding Racers", DialogText.muted("Checking player names…"), List.of(), p -> roster(p, draft));
        UUID token = UUID.randomUUID(); lookups.put(player.getUniqueId(), token);
        future.whenComplete((entries, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !player.hasPermission("contender.master") || !token.equals(lookups.get(player.getUniqueId()))) return;
                lookups.remove(player.getUniqueId(), token);
                if (failure != null) { Dialogs.tell(player, RaceManager.rootMessage(failure)); roster(player, draft); return; }
                try { entries.forEach(e -> e.players().forEach(plugin.getDuelManager()::requireEligible)); review(player, draft, entries); }
                catch (IllegalArgumentException error) { Dialogs.tell(player, error.getMessage()); roster(player, draft); }
            });
        });
    }
    private void review(Player player, Draft draft, List<TournamentEntry> entries) {
        form(player, "Review Mace Race", DialogText.paragraphs(TabStyle.read(plugin.getConfig()).header(draft.title()),
                DialogText.lines(DialogText.detail("Course", mapName(draft.map())), kitDetail(draft.kitId()), DialogText.detail("Racers", String.valueOf(entries.size())),
                        DialogText.detail("Checkpoint Jump", String.valueOf(draft.advance())), DialogText.detail("Time Limit", draft.minutes() == 0 ? "No limit" : draft.minutes() + " minutes"),
                        groundReturnDetail(draft.map())),
                DialogText.muted((draft.kitId().isBlank() ? "Wind Burst I, II, and III maces\nLunge III spear · 64 wind charges · 16 pearls\n\n" : "Only maces and spears count toward checkpoints.\n\n")
                        + "Hunger stays full. Racers take no damage.\nThe checkpoint bed uses the last hotbar slot.")), List.of(),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> rules(p, draft)),
                button(player, DialogIcon.SAVE, "Create Race", ACCENT, 150, (p, v) -> {
                    plugin.getRaceManager().create(draft.title(), draft.map(), draft.advance(), draft.minutes(), entries, draft.kitId()); open(p);
                }));
    }
    public void courses(Player player, int requested) {
        var maps = new ArrayList<>(plugin.getMapManager().getMaps());
        int pages = Math.max(1, (maps.size() + 7) / 8), page = Math.clamp(requested, 0, pages - 1);
        List<ActionButton> actions = new ArrayList<>();
        for (int i = page * 8; i < Math.min(maps.size(), page * 8 + 8); i++) {
            var map = maps.get(i); actions.add(button(player, DialogIcon.MAP, map.getDisplayName(), TEXT, 150, (p, v) -> course(p, map.getId())));
        }
        Dialogs.navigationRow(actions, page > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> courses(p, page - 1)) : null,
                page + 1 < pages ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> courses(p, page + 1)) : null, 150);
        dialogs.show(player, "Race Courses", List.of(DialogBody.plainMessage(DialogText.muted(maps.isEmpty() ? "Save a map selection in Maps first." : "Choose a map to set up its race course."), 320)), List.of(), actions, 2, 150,
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> new TournamentDialogs(plugin).tools(p)));
    }
    private void course(Player player, String id) {
        var manager = plugin.getRaceManager(); var course = manager.course(id);
        menu(player, mapName(id), DialogText.paragraphs(DialogText.lines(DialogText.detail("Map ID", id),
                        DialogText.detail("Checkpoint Jump", String.valueOf(course.maxAdvance())), DialogText.detail("Finish Mob", course.finishName()),
                        groundReturnDetail(id)),
                DialogText.muted("Name mobs #1, #2, #3, and so on, inside the selection.\nUse the numbering tool to name new mobs as you spawn them.\n\nSave the course after changing its mobs or blocks.")), List.of(
                action(player, DialogIcon.SAVE, "Save Course & Rebuild Copies", ACCENT, p -> {
                    p.closeDialog(); Dialogs.tell(p, "Checking the course and rebuilding its copies…");
                    manager.saveCourse(id).whenComplete((ignored, error) -> {
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) Dialogs.tell(p, error == null ? "Course saved. Its copies are ready." : RaceManager.rootMessage(error)); });
                    });
                }),
                action(player, DialogIcon.SPAWN, "Set Start Here", TEXT, p -> { manager.setStart(id, p); course(p, id); }),
                action(player, DialogIcon.NAME, manager.numbering(player, id) ? "Stop Numbering Mobs" : "Number New Mobs", TEXT, p -> { manager.numberSpawns(p, id); p.closeDialog(); }),
                action(player, DialogIcon.SAVE, "Mark Finish Mob", TEXT, p -> { manager.markFinish(p, id); p.closeDialog(); }),
                action(player, DialogIcon.SETTINGS, "Course Rules", TEXT, p -> courseRules(p, id)),
                action(player, DialogIcon.MACE, "Race Kits", TEXT, this::kits)), p -> courses(p, 0));
    }
    private void courseRules(Player player, String id) {
        var course = plugin.getRaceManager().course(id);
        form(player, "Course Rules", DialogText.muted("These rules apply to races on this course.\n\nWith Return on Ground enabled, landing returns you to your last checkpoint, or the start.\nYou can stand at the start until you first leave the ground."), List.of(
                DialogInput.text("advance", DialogIcon.STEP.label("Maximum Checkpoint Jump")).initial(String.valueOf(course.maxAdvance())).maxLength(4).width(300).build(),
                DialogInput.text("finish", DialogIcon.NAME.label("Finish Mob Name")).initial(course.finishName()).maxLength(48).width(300).build(),
                DialogInput.text("height", DialogIcon.JUMP.label("Return Height Above Mob")).initial(String.valueOf((int) course.returnHeight())).maxLength(2).width(300).build(),
                DialogInput.singleOption("return_on_ground", DialogIcon.BOOTS.label("Return on Ground"), List.of(
                        Dialogs.option("true", "Enabled", course.returnOnGround()),
                        Dialogs.option("false", "Disabled", !course.returnOnGround()))).width(300).build()),
                button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> course(p, id)),
                button(player, DialogIcon.SAVE, "Save Rules", ACCENT, 150, (p, v) -> {
                    boolean returnOnGround = switch (Dialogs.text(v, "return_on_ground")) {
                        case "true" -> true;
                        case "false" -> false;
                        default -> throw new IllegalArgumentException("Choose whether landing returns you to your checkpoint.");
                    };
                    plugin.getRaceManager().configure(new RaceCourse(id, integer(v, "advance", 1, 1000), Dialogs.text(v, "finish"), integer(v, "height", 1, 20), returnOnGround));
                    course(p, id);
                }));
    }
    private Component groundReturnDetail(String mapId) {
        return DialogText.detail("Return on Ground", plugin.getRaceManager().course(mapId).returnOnGround() ? "Enabled" : "Disabled");
    }
    private void standings(Player player, UUID id, int page) {
        plugin.getRaceManager().requireCurrent(id); var run = plugin.getRaceManager().current(); var racers = run.standings();
        int last = Math.max(0, (racers.size() - 1) / 12), selected = Math.clamp(page, 0, last);
        Component rows = Component.empty();
        for (var racer : racers.subList(selected * 12, Math.min(racers.size(), selected * 12 + 12))) rows = rows.append(
                Component.text((racer.place() > 0 ? "#" + racer.place() + " " : "") + racer.name(), TEXT)).append(text("  " + result(racer), MUTED)).appendNewline();
        List<ActionButton> actions = new ArrayList<>();
        Dialogs.navigationRow(actions, selected > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> standings(p, id, selected - 1)) : null,
                selected < last ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> standings(p, id, selected + 1)) : null, 150);
        dialogs.show(player, "Race Leaderboard", List.of(DialogBody.plainMessage(rows, 320)), List.of(), actions, 2, 150, button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> open(p)));
    }
    private String result(RaceRun.Racer racer) { return racer.finishNanos() >= 0 ? RaceRun.time(racer.finishNanos()) : racer.withdrawn() ? "Did Not Finish" : "Checkpoint " + racer.checkpoint(); }
    private void racers(Player player, UUID id, int page) {
        plugin.getRaceManager().requireCurrent(id); var roster = plugin.getRaceManager().current().racers();
        int last = (roster.size() - 1) / 10, selected = Math.clamp(page, 0, last);
        List<ActionButton> actions = new ArrayList<>();
        for (var racer : roster.subList(selected * 10, Math.min(roster.size(), selected * 10 + 10))) if (!racer.done()) actions.add(button(player, DialogIcon.PLAYERS, racer.name(), TEXT, 150, (p, v) -> {
            menu(p, "Withdraw Racer", DialogText.muted("Return " + racer.name() + " to the lobby and mark them Did Not Finish?"), List.of(action(p, DialogIcon.CLOSE, "Withdraw " + racer.name(), DANGER, who -> {
                plugin.getRaceManager().requireCurrent(id); plugin.getRaceManager().withdraw(racer.id()); open(who);
            })), this::open);
        }));
        Dialogs.navigationRow(actions, selected > 0 ? button(player, DialogIcon.BACK, "Previous", MUTED, 150, (p, v) -> racers(p, id, selected - 1)) : null,
                selected < last ? button(player, DialogIcon.NEXT, "Next", ACCENT, 150, (p, v) -> racers(p, id, selected + 1)) : null, 150);
        dialogs.show(player, "Manage Racers", List.of(DialogBody.plainMessage(DialogText.muted("Choose a racer to withdraw from this race."), 320)), List.of(), actions, 2, 150, button(player, DialogIcon.BACK, "Back", MUTED, 150, (p, v) -> open(p)));
    }
    public void kits(Player player) {
        menu(player, "Race Kits", DialogText.muted("Edit the Mace Race loadout, or create another saved kit.\nChoose it when creating a race.\n\nThe race keeps hunger full and prevents damage.\nLeave one inventory slot empty for the checkpoint bed."), List.of(
                action(player, DialogIcon.MACE, "Edit Mace Race Kit", ACCENT, p -> {
                    Kit kit = plugin.getKitManager().getKit(RaceKit.EDITABLE_DEFAULT_ID);
                    if (kit == null) {
                        kit = RaceKit.defaultKit(RaceKit.EDITABLE_DEFAULT_ID);
                        plugin.getKitManager().saveKit(kit);
                    }
                    p.closeDialog();
                    KitEditorGUI.open(p, kit, false);
                }),
                action(player, DialogIcon.DUEL, "All Saved Kits", TEXT, p -> new KitDialogs(plugin).open(p, this::kits))),
                p -> new TournamentDialogs(plugin).tools(p));
    }
    private List<SingleOptionDialogInput.OptionEntry> kitOptions(String selected) {
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        options.add(SingleOptionDialogInput.OptionEntry.create(BUILT_IN_KIT, DialogIcon.MACE.label("Default Mace Race Kit", TEXT), selected.isBlank()));
        plugin.getKitManager().getKits().stream().sorted(Comparator.comparing(Kit::getDisplayName))
                .forEach(kit -> options.add(SingleOptionDialogInput.OptionEntry.create(kit.getId(), KitIcons.label(kit), selected.equals(kit.getId()))));
        return options;
    }
    private String readKit(DialogResponseView view) {
        String id = Dialogs.text(view, "kit");
        if (BUILT_IN_KIT.equals(id)) return "";
        Kit kit = plugin.getKitManager().getKit(id);
        if (kit == null) throw new IllegalArgumentException("Choose a saved kit or the default Mace Race kit.");
        RaceKit.validate(kit);
        return id;
    }
    private Component kitDetail(String id) {
        Component label;
        if (id.isBlank()) label = DialogIcon.MACE.label("Default Mace Race Kit", TEXT);
        else {
            Kit kit = plugin.getKitManager().getKit(id);
            label = kit == null ? text(id + " (missing)", DANGER) : KitIcons.label(kit);
        }
        return DialogText.muted("Kit: ").append(label);
    }
    private String mapName(String id) { var map = plugin.getMapManager().getMap(id); return map == null ? id : map.getDisplayName(); }
    private static int integer(DialogResponseView view, String key, int min, int max) {
        try { int value = Integer.parseInt(Dialogs.text(view, key)); if (value >= min && value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Enter a whole number between " + min + " and " + max + ".");
    }
}
