package me.advait.contender.minigame.race;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.dialog.*;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.minigame.MinigameType;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

import static me.advait.contender.dialog.DialogPalette.*;

/** Mace Race: course setup, the create form, and reloading the last results. */
public final class RaceType implements MinigameType, AutoCloseable {
    private static final int WIDE = 300, NAV = 150;
    private final Contender plugin;
    private final RaceCourses courses;
    private final RaceEditor editor;
    private final Dialogs dialogs;

    public RaceType(Contender plugin) {
        this.plugin = plugin;
        this.courses = new RaceCourses(plugin);
        this.dialogs = new Dialogs(plugin);
        this.editor = new RaceEditor(plugin, courses, player -> {
            String id = editorCourse(player);
            if (id != null) course(player, id);
        });
        editor.enable();
    }

    private String editorCourse(Player player) { return editor.editingCourse(player); }

    public RaceCourses courses() { return courses; }
    public RaceEditor editor() { return editor; }

    @Override public void close() { editor.disable(); }

    @Override public String id() { return "mace_race"; }
    @Override public String name() { return "Mace Race"; }
    @Override public String description() { return "Bounce off checkpoint mobs with Wind Burst maces. Fastest time wins."; }
    @Override public DialogIcon icon() { return DialogIcon.MACE; }
    @Override public boolean hasSetup() { return true; }

    @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
        RaceCourse course = courses.get(options.getOrDefault("course", ""));
        if (course == null) throw new IllegalArgumentException("Choose a course.");
        String problem = course.problem();
        if (problem != null) throw new IllegalArgumentException(course.name() + ": " + problem);
        if (kit != null) RaceKit.validate(kit);
        int minutes = GameForm.intOption(options, "minutes", 0, 60, 0);
        return new RaceGame(plugin, this, UUID.randomUUID(), name, roster, course, kit, minutes * 60);
    }

    @Override public void openCreate(Player director) {
        List<RaceCourse> ready = courses.all().stream().filter(course -> course.problem() == null).toList();
        if (ready.isEmpty()) {
            dialogs.show(director, "No Courses Yet", List.of(DialogBody.plainMessage(DialogText.muted("Build a course first: Setup Tools → Minigames → Mace Race."), 320)),
                    List.of(), List.of(dialogs.button(director, DialogIcon.MACE.label("Course Setup", ACCENT), null, true, WIDE, (p, view) -> openSetup(p))),
                    1, NAV, dialogs.button(director, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).formats(p)));
            return;
        }
        DialogInput coursePicker = DialogInput.singleOption("course", DialogIcon.RACE.label("Course"), ready.stream()
                .map(course -> Dialogs.option(course.id(), course.name() + " (" + checkpoints(course) + ")", course == ready.getFirst())).toList()).width(WIDE).build();
        new GameForm(plugin).open(director, new GameForm.Spec("Mace Race", "Mace Race",
                "Pick a course and the racers.", false, true, true,
                List.of(coursePicker, DialogInput.singleOption("minutes", DialogIcon.CLOCK.label("Time Limit"), java.util.stream.Stream.of(0, 3, 5, 10, 15, 20, 30)
                        .map(minutes -> Dialogs.option(Integer.toString(minutes), minutes == 0 ? "No limit" : minutes + " minutes", minutes == 0)).toList()).width(WIDE).build()), 1),
                (p, result) -> {
                    Minigame game = create(result.name(), null, result.kit(), result.roster(), result.values());
                    plugin.getMinigames().select(game);
                    new MinigameDialogs(plugin).control(p, game);
                }, p -> new TournamentDialogs(plugin).formats(p));
    }

    @Override public Minigame restore() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
        if (!yaml.contains("id")) return null;
        RaceCourse course = courses.get(yaml.getString("extra.course", ""));
        if (course == null) return null;
        Kit kit = plugin.getKitManager().getKit(yaml.getString("extra.kit", ""));
        RaceGame game = new RaceGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()),
                Minigame.savedRoster(yaml), course, kit, yaml.getInt("extra.time-limit"));
        game.readSaved(yaml);
        return game;
    }

    // ---- Setup dialogs -------------------------------------------------------------------------

    @Override public void openSetup(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogs.button(player, DialogIcon.SAVE.label("New Course", ACCENT), DialogText.muted("Name it, then build it where you stand."), true, WIDE, (p, view) -> create(p)));
        for (RaceCourse course : courses.all()) {
            String problem = course.problem();
            Component label = DialogIcon.RACE.label(course.name() + "  ", TEXT).append(text(problem == null ? checkpoints(course) : "not ready", problem == null ? MUTED : WARNING));
            buttons.add(dialogs.button(player, label, DialogText.muted(problem == null ? "Edit, test or change rules." : problem), true, WIDE, (p, view) -> course(p, course.id())));
        }
        for (String legacy : courses.legacyCourses()) {
            var map = plugin.getMapManager().getMap(legacy);
            buttons.add(dialogs.button(player, DialogIcon.REFRESH.label("Import Old Course: " + (map == null ? legacy : map.getDisplayName()), TEXT),
                    DialogText.muted("Converts its numbered mobs into checkpoints and removes the old mobs."), true, WIDE, (p, view) -> {
                        p.closeDialog();
                        Dialogs.tell(p, "Importing… this loads the old map for a moment.");
                        courses.importLegacy(legacy).whenComplete((course, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!p.isOnline()) return;
                            if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                            else { Dialogs.tell(p, "Imported " + course.name() + " with " + course.size() + (course.size() == 1 ? " checkpoint." : " checkpoints.")); course(p, course.id()); }
                        }));
                    }));
        }
        dialogs.show(player, "Mace Race Courses", List.of(DialogBody.plainMessage(DialogText.lines(
                DialogText.muted("Racers hit the checkpoints in order."),
                DialogText.muted("The last one is the finish. They can float midair.")), 340)),
                List.of(), buttons, 1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).minigameTools(p)));
    }

    private void create(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> openSetup(p)),
                dialogs.button(player, DialogIcon.NEXT.label("Start Building", ACCENT), null, true, NAV, (p, view) -> {
                    String name = Dialogs.text(view, "name");
                    if (name.isBlank()) throw new IllegalArgumentException("Give the course a name.");
                    RaceCourse course = courses.create(name, p.getWorld());
                    course.setStart(p.getLocation());
                    courses.save();
                    p.closeDialog();
                    editor.start(p, course);
                }), NAV);
        dialogs.show(player, "New Course", List.of(DialogBody.plainMessage(DialogText.muted(
                "The start is set to where you stand now. You'll get building tools next."), 320)),
                List.of(Dialogs.text("name", "Course name", "", 48)), buttons, 2, NAV, null);
    }

    /** "3 checkpoints + finish", or "Finish only" for a one-checkpoint course. */
    private static String checkpoints(RaceCourse course) {
        int before = Math.max(0, course.size() - 1);
        if (before == 0) return "Finish only";
        return before + (before == 1 ? " checkpoint" : " checkpoints") + " + finish";
    }

    private RaceCourse require(String id) {
        RaceCourse course = courses.get(id);
        if (course == null) throw new IllegalStateException("That course was deleted.");
        return course;
    }

    private void course(Player player, String id) {
        RaceCourse course = require(id);
        String problem = course.problem();
        Component body = DialogText.lines(
                DialogText.detail("Checkpoints", course.size() == 0 ? "None yet" : (course.size() - 1) + " + finish"),
                DialogText.detail("World", course.worldName()),
                DialogText.detail("Checkpoint jump", "up to " + course.maxJump()),
                DialogText.detail("Return height", course.returnHeight() + " blocks"),
                DialogText.detail("Landing sends you back", course.returnOnGround() ? "Yes" : "No"),
                problem == null ? text("Ready to race", SUCCESS) : text(problem, WARNING));
        List<ActionButton> buttons = new ArrayList<>();
        boolean editing = editor.editingCourse(player) != null && editor.editingCourse(player).equals(id);
        buttons.add(dialogs.button(player, DialogIcon.SETTINGS.label(editing ? "Keep Building" : "Build in the World", ACCENT),
                DialogText.muted("Get the checkpoint tools and see the course."), true, WIDE, (p, view) -> { p.closeDialog(); if (!editing) editor.start(p, require(id)); }));
        if (editing) buttons.add(dialogs.button(player, DialogIcon.SAVE.label("Stop Building", TEXT), DialogText.muted("Get your items back."), true, WIDE,
                (p, view) -> { editor.stop(p); course(p, id); }));
        buttons.add(dialogs.button(player, DialogIcon.SPAWN.label("Go to Start", TEXT), null, true, WIDE, (p, view) -> {
            var start = require(id).startLocation();
            if (start == null) throw new IllegalStateException("Set the start first.");
            p.teleport(start);
            p.closeDialog();
        }));
        buttons.add(dialogs.button(player, DialogIcon.BOARD.label("Rules", TEXT), null, true, WIDE, (p, view) -> rules(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.NAME.label("Rename", TEXT), null, true, WIDE, (p, view) -> rename(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.CLOSE.label("Delete Course", DANGER), null, true, WIDE, (p, view) -> confirmDelete(p, id)));
        dialogs.show(player, course.name(), List.of(DialogBody.plainMessage(body, 320)), List.of(), buttons, 1, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> openSetup(p)));
    }

    private void rules(Player player, String id) {
        RaceCourse course = require(id);
        List<DialogInput> inputs = List.of(
                GameForm.number("jump", DialogIcon.NEXT, "Checkpoint Jump", 1, 50, course.maxJump(), 1, null),
                GameForm.number("height", DialogIcon.JUMP, "Return Height", 1, 30, (int) Math.round(course.returnHeight()), 1, "%s: %s blocks"),
                DialogInput.singleOption("ground", DialogIcon.SPAWN.label("Landing"), List.of(
                        Dialogs.option("true", "Sends you back to your checkpoint", course.returnOnGround()),
                        Dialogs.option("false", "Is allowed", !course.returnOnGround()))).width(WIDE).build());
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> course(p, id)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), null, true, NAV, (p, view) -> {
                    require(id).rules(Dialogs.number(view, "jump", 1, 50), Dialogs.number(view, "height", 1, 30), Dialogs.text(view, "ground").equals("true"));
                    courses.save();
                    Dialogs.tell(p, "Rules saved.");
                    course(p, id);
                }), NAV);
        dialogs.show(player, "Rules", List.of(DialogBody.plainMessage(DialogText.lines(
                DialogText.muted("Checkpoint jump: how many checkpoints one hit can skip ahead."),
                DialogText.muted("Return height: how far above a checkpoint mob you reappear.")), 320)), inputs, buttons, 2, NAV, null);
    }

    private void rename(Player player, String id) {
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> course(p, id)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), null, true, NAV, (p, view) -> {
                    require(id).name(Dialogs.text(view, "name"));
                    courses.save();
                    course(p, id);
                }), NAV);
        dialogs.show(player, "Rename", List.of(), List.of(Dialogs.text("name", "Course name", require(id).name(), 48)), buttons, 2, NAV, null);
    }

    private void confirmDelete(Player player, String id) {
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> course(p, id)),
                dialogs.button(player, DialogIcon.CLOSE.label("Delete", DANGER), null, true, NAV, (p, view) -> {
                    if (editor.courseBeingEdited(id)) throw new IllegalStateException("Someone is building this course.");
                    courses.delete(id);
                    openSetup(p);
                }), NAV);
        dialogs.show(player, "Delete " + require(id).name() + "?", List.of(DialogBody.plainMessage(DialogText.muted("This can't be undone."), 320)), List.of(), buttons, 2, NAV, null);
    }
}
