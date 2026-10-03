package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static me.advait.contender.dialog.DialogPalette.*;

/** /arena: save a WorldEdit selection as a map, set its spawns, and watch its copies prepare. */
public final class ArenaDialogs {
    private static final int WIDE = 300, NAV = 150, PER_PAGE = 8;
    private final Contender plugin;
    private final Dialogs dialogs;

    public ArenaDialogs(Contender plugin) {
        this.plugin = plugin;
        this.dialogs = new Dialogs(plugin);
    }

    public void open(Player player) { open(player, 0); }

    private void open(Player player, int requestedPage) {
        List<ArenaMap> maps = new ArrayList<>(plugin.getMapManager().getMaps());
        int pages = Math.max(1, (maps.size() + PER_PAGE - 1) / PER_PAGE), page = Math.clamp(requestedPage, 0, pages - 1);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogs.button(player, DialogIcon.SAVE.label("New Map", ACCENT), DialogText.muted("Save your WorldEdit selection as a map."),
                true, NAV, (p, view) -> create(p)));
        for (ArenaMap map : maps.subList(page * PER_PAGE, Math.min(maps.size(), (page + 1) * PER_PAGE))) {
            int ready = plugin.getArenas().ready(map.getId());
            Component tooltip = DialogText.lines(DialogText.muted(map.isComplete() ? ready + " of " + map.getCopies() + " copies ready" : "Needs both team spawns"),
                    DialogText.muted(plugin.getArenas().readiness(map.getId())));
            buttons.add(dialogs.button(player, DialogIcon.MAP.label(map.getDisplayName(), map.isComplete() ? TEXT : WARNING), tooltip, true, NAV, (p, view) -> edit(p, map.getId())));
        }
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK.label("Previous", TEXT), null, true, NAV, (p, view) -> open(p, page - 1)) : null,
                page + 1 < pages ? dialogs.button(player, DialogIcon.NEXT.label("Next", TEXT), null, true, NAV, (p, view) -> open(p, page + 1)) : null, NAV);
        List<DialogBody> body = new ArrayList<>();
        Component intro = DialogText.muted("Each map is copied into its own arenas, so several matches can run at once.");
        if (maps.stream().anyMatch(map -> !map.isComplete())) intro = DialogText.lines(intro, DialogText.muted("Maps in orange still need spawns."));
        body.add(DialogBody.plainMessage(intro, 320));
        if (pages > 1) body.add(DialogBody.plainMessage(DialogText.page(page + 1, pages), 320));
        dialogs.show(player, "Maps", body, List.of(), buttons, 2, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).tools(p)));
    }

    private ArenaMap map(String id) {
        ArenaMap map = plugin.getMapManager().getMap(id);
        if (map == null) throw new IllegalArgumentException("That map no longer exists.");
        return map;
    }

    public void create(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> open(p)),
                dialogs.button(player, DialogIcon.SAVE.label("Save Map", ACCENT), null, true, NAV, (p, view) -> {
                    String name = Dialogs.text(view, "name");
                    if (name.isBlank()) throw new IllegalArgumentException("Give the map a name.");
                    var result = plugin.getArenas().create(p, name, Dialogs.number(view, "copies", 1, 100));
                    p.closeDialog();
                    acknowledge(p, result);
                    result.whenComplete((map, failure) -> {
                        if (!p.isOnline()) return;
                        if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                        else { Dialogs.tell(p, map.getDisplayName() + " saved. Now set both team spawns."); edit(p, map.getId()); }
                    });
                }), NAV);
        dialogs.show(player, "New Map", List.of(DialogBody.plainMessage(DialogText.lines(
                DialogText.muted("Saves everything inside your WorldEdit selection, including"),
                DialogText.muted("air above the floor and any decorations. Spawns come next.")), 320)), List.of(
                Dialogs.text("name", "Map name", "", 64),
                Dialogs.number("copies", "Arena copies (matches at once)", Math.clamp(plugin.getConfig().getInt("arenas.copies-per-map", 20), 1, 100), 1, 100, 1)),
                buttons, 2, NAV, null);
    }

    public void edit(Player player, String id) {
        ArenaMap map = map(id);
        Component body = DialogText.paragraphs(
                DialogText.lines(
                        spawnLine("Team 1 spawn", map.getTeam1Point() != null),
                        spawnLine("Team 2 spawn", map.getTeam2Point() != null),
                        DialogText.detail("Spectator spawn", map.getSpectatorPoint() == null ? "Uses team 1" : "Set", map.getSpectatorPoint() == null ? MUTED : SUCCESS),
                        DialogText.detail("Hill", map.getHillPoint() == null ? "Middle of the map" : "Set", map.getHillPoint() == null ? MUTED : SUCCESS)),
                DialogText.detail("Copies", plugin.getArenas().readiness(id), plugin.getArenas().ready(id) > 0 ? SUCCESS : WARNING),
                DialogText.muted("Stand on a spawn and face the right way, then click its button."));
        List<ActionButton> buttons = new ArrayList<>();
        for (String side : List.of("1", "2", "spectator")) {
            String label = side.equals("spectator") ? "Set Spectator Spawn Here" : "Set Team " + side + " Spawn Here";
            buttons.add(dialogs.button(player, DialogIcon.SPAWN.label(label, TEXT), null, true, WIDE, (p, view) -> {
                plugin.getArenas().setSpawn(map(id), side, p.getLocation());
                Dialogs.tell(p, side.equals("spectator") ? "Spectator spawn saved." : "Team " + side + " spawn saved.");
                edit(p, id);
            }));
        }
        buttons.add(dialogs.button(player, DialogIcon.CROWN.label("Set Hill Here", TEXT),
                DialogText.muted("King of the Hill's hill is a circle around where you stand. Without one, it's the middle of the map."), true, WIDE, (p, view) -> {
                    plugin.getArenas().setSpawn(map(id), "hill", p.getLocation());
                    Dialogs.tell(p, "Hill saved. It's a circle around this spot.");
                    edit(p, id);
                }));
        if (map.getHillPoint() != null) buttons.add(dialogs.button(player, DialogIcon.REFRESH.label("Use the Middle for the Hill", TEXT), null, true, WIDE, (p, view) -> {
            plugin.getArenas().clearHill(map(id));
            Dialogs.tell(p, "The hill is back in the middle of the map.");
            edit(p, id);
        }));
        buttons.add(dialogs.button(player, DialogIcon.SAVE.label("Save Current Blocks", TEXT), DialogText.muted("Use after changing the original map. Copies update automatically."), true, WIDE, (p, view) -> {
            var result = plugin.getArenas().saveBlocks(map(id));
            p.closeDialog();
            acknowledge(p, result);
            result.whenComplete((ignored, failure) -> {
                if (!p.isOnline()) return;
                if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                else Dialogs.tell(p, "Blocks saved. Copies are updating.");
            });
        }));
        buttons.add(dialogs.button(player, DialogIcon.NAME.label("Name and Copies", TEXT), null, true, WIDE, (p, view) -> settings(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.CLOSE.label("Delete Map", DANGER), null, true, WIDE, (p, view) -> confirmDelete(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.REFRESH.label("Refresh", TEXT), DialogText.muted("Update the copy progress."), true, WIDE, (p, view) -> edit(p, id)));
        dialogs.show(player, map.getDisplayName(), List.of(DialogBody.plainMessage(body, 320)), List.of(), buttons, 1, NAV,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> open(p)));
    }

    private static Component spawnLine(String label, boolean set) {
        return DialogText.detail(label, set ? "Set" : "Not set", set ? SUCCESS : WARNING);
    }

    private void settings(Player player, String id) {
        ArenaMap map = map(id);
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> edit(p, id)),
                dialogs.button(player, DialogIcon.SAVE.label("Save", ACCENT), null, true, NAV, (p, view) -> {
                    plugin.getArenas().configure(map(id), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    edit(p, id);
                }), NAV);
        dialogs.show(player, "Name and Copies", List.of(DialogBody.plainMessage(DialogText.muted("More copies let more matches run at the same time."), 320)), List.of(
                Dialogs.text("name", "Map name", map.getDisplayName(), 64),
                Dialogs.number("copies", "Arena copies", map.getCopies(), 1, 100, 1)), buttons, 2, NAV, null);
    }

    private void confirmDelete(Player player, String id) {
        ArenaMap map = map(id);
        List<ActionButton> buttons = new ArrayList<>();
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> edit(p, id)),
                dialogs.button(player, DialogIcon.CLOSE.label("Delete", DANGER), null, true, NAV, (p, view) -> {
                    plugin.getArenas().delete(map(id));
                    Dialogs.tell(p, map.getDisplayName() + " deleted.");
                    open(p);
                }), NAV);
        dialogs.show(player, "Delete " + map.getDisplayName() + "?", List.of(DialogBody.plainMessage(DialogText.muted(
                "The original build stays where it is. Its arena copies stop being used."), 320)), List.of(), buttons, 2, NAV, null);
    }

    private void acknowledge(Player player, CompletableFuture<?> result) {
        if (result.isDone()) return;
        Dialogs.tell(player, "Saving the map. Large maps can take a little while.");
    }
}
