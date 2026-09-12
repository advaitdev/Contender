package me.advait.contender.dialog;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

public final class ArenaDialogs {
    private static final String MAP_ID_INPUT = "map_id";
    private final Contender plugin;
    private final Dialogs dialogs;
    public ArenaDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) { open(player, 0); }
    private void open(Player player, int page) {
        List<ArenaMap> maps = new ArrayList<>(plugin.getMapManager().getMaps());
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = page * 8; i < Math.min(page * 8 + 8, maps.size()); i++) {
            ArenaMap map = maps.get(i);
            buttons.add(dialogs.button(player, DialogIcon.MAP, map.getDisplayName(), (p, view) -> edit(p, map.getId())));
        }
        buttons.add(dialogs.button(player, DialogIcon.MAP, "Create Map", (p, view) -> create(p)));
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK, "Previous Page", (p, view) -> open(p, page - 1)) : null,
                (page + 1) * 8 < maps.size() ? dialogs.button(player, DialogIcon.NEXT, "Next Page", (p, view) -> open(p, page + 1)) : null);
        dialogs.show(player, "Maps", DialogText.paragraphs(
                DialogText.muted("Select a map to edit its spawns or check its arena copies.\nCopies prepare automatically and reset after use."),
                DialogText.muted("Creating a map?\nSelect both corners with the WorldEdit wand first."),
                DialogText.page(page + 1, Math.max(1, (maps.size() + 7) / 8))), List.of(), buttons);
    }
    private ArenaMap map(String id) {
        ArenaMap map = plugin.getMapManager().getMap(id);
        if (map == null) throw new IllegalArgumentException("That map no longer exists.");
        return map;
    }
    public void create(Player player) {
        dialogs.show(player, "Create Map", DialogText.paragraphs(
                DialogText.muted("Save the selected blocks and entities as a map."),
                DialogText.lines(DialogText.detail("Map ID", "mines"),
                        DialogText.muted("A unique short name. Use lowercase letters, numbers, - or _.")),
                DialogText.lines(DialogText.detail("Map name", "The Mines"),
                        DialogText.muted("The name shown when choosing a map."))), List.of(
                Dialogs.text(MAP_ID_INPUT, "Map ID", "", 40), Dialogs.text("name", "Map name", "", 64),
                Dialogs.number("copies", "Arena copies", Math.clamp(plugin.getConfig().getInt("arenas.copies-per-map", 20), 1, 100), 1, 100, 1)),
                List.of(dialogs.button(player, DialogIcon.SAVE, "Save Selection", (p, view) -> {
                    var result = plugin.getArenaManager().create(p, Dialogs.text(view, MAP_ID_INPUT), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    p.closeDialog();
                    result.whenComplete((map, failure) -> {
                        if (!p.isOnline()) return;
                        if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                        else { Dialogs.tell(p, map.getDisplayName() + " saved. Set both team spawns to prepare its arenas automatically."); edit(p, map.getId()); }
                    });
                }), dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> open(p))));
    }
    public void edit(Player player, String id) {
        ArenaMap map = map(id);
        int ready = plugin.getArenaManager().available(id);
        Component body = DialogText.paragraphs(
                DialogText.muted("ID: " + id),
                DialogText.detail("Arena copies", plugin.getArenaManager().readiness(id),
                        ready > 0 ? DialogPalette.SUCCESS : DialogPalette.WARNING),
                DialogText.muted("Copies prepare automatically and reset after use."),
                DialogText.lines(
                        DialogText.detail("Team 1 spawn", map.getTeam1Point() == null ? "Not set" : "Saved",
                                map.getTeam1Point() == null ? DialogPalette.WARNING : DialogPalette.SUCCESS),
                        DialogText.detail("Team 2 spawn", map.getTeam2Point() == null ? "Not set" : "Saved",
                                map.getTeam2Point() == null ? DialogPalette.WARNING : DialogPalette.SUCCESS),
                        DialogText.detail("Spectator spawn", map.getSpectatorPoint() == null ? "Uses team 1" : "Saved",
                                map.getSpectatorPoint() == null ? DialogPalette.MUTED : DialogPalette.SUCCESS)),
                DialogText.muted("Stand at a spawn, then choose its button below."));
        List<ActionButton> buttons = new ArrayList<>();
        for (String side : List.of("1", "2", "spectator")) {
            String label = side.equals("spectator") ? "Set Spectator Spawn" : "Set Team " + side + " Spawn";
            buttons.add(dialogs.button(player, DialogIcon.SPAWN, label, (p, view) -> {
                plugin.getArenaManager().setSpawn(map(id), side, p.getLocation());
                p.closeDialog();
                Dialogs.tell(p, "Spawn saved.");
            }));
        }
        buttons.add(dialogs.button(player, DialogIcon.NAME, "Name and Copy Count", (p, view) -> settings(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.SAVE, "Save Current Blocks", (p, view) -> {
            p.closeDialog();
            plugin.getArenaManager().saveBlocks(map(id)).whenComplete((ignored, failure) -> {
                if (p.isOnline()) {
                    if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                    else Dialogs.tell(p, "Template saved. Arena copies are updating automatically.");
                }
            });
        }));
        Dialogs.navigationRow(buttons,
                dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> open(p)),
                dialogs.button(player, DialogIcon.REFRESH, "Check Arena Copies", (p, view) -> {
                    var preparation = plugin.getArenaManager().prepare(map(id));
                    edit(p, id);
                    preparation.whenComplete((ignored, failure) -> {
                        if (plugin.isEnabled() && p.isOnline() && failure != null) {
                            Dialogs.error(p, Dialogs.message(failure));
                        }
                    });
                }));
        dialogs.show(player, map.getDisplayName(), body, List.of(), buttons);
    }
    private void settings(Player player, String id) {
        ArenaMap map = map(id);
        dialogs.show(player, "Map Settings", DialogText.paragraphs(DialogText.muted("ID: " + id),
                DialogText.muted("Changes apply to arena copies automatically.")), List.of(
                Dialogs.text("name", "Map name", map.getDisplayName(), 64),
                Dialogs.number("copies", "Arena copies", map.getCopies(), 1, 100, 1)),
                List.of(dialogs.button(player, DialogIcon.SAVE, "Save", (p, view) -> {
                    plugin.getArenaManager().configure(map(id), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    edit(p, id);
                }), dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> edit(p, id))));
    }
}
