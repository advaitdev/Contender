package me.advait.contender.dialog;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import io.papermc.paper.registry.data.dialog.ActionButton;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

public final class ArenaDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public ArenaDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) { open(player, 0); }
    private void open(Player player, int page) {
        List<ArenaMap> maps = new ArrayList<>(plugin.getMapManager().getMaps());
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = page * 8; i < Math.min(page * 8 + 8, maps.size()); i++) {
            ArenaMap map = maps.get(i);
            buttons.add(dialogs.button(player, map.getDisplayName(), (p, view) -> edit(p, map.getId())));
        }
        buttons.add(dialogs.button(player, "Create map", (p, view) -> create(p)));
        if (page > 0) buttons.add(dialogs.button(player, "Previous page", (p, view) -> open(p, page - 1)));
        if ((page + 1) * 8 < maps.size()) buttons.add(dialogs.button(player, "Next page", (p, view) -> open(p, page + 1)));
        dialogs.show(player, "Maps", "Select a map to set its spawns or prepare arena copies.\nTo create a map, select its region with the WorldEdit wand first.", List.of(), buttons);
    }
    private ArenaMap map(String id) {
        ArenaMap map = plugin.getMapManager().getMap(id);
        if (map == null) throw new IllegalArgumentException("That map no longer exists.");
        return map;
    }
    public void create(Player player) {
        dialogs.show(player, "Create map", "The selection's blocks and entities will be saved as the map template.", List.of(
                Dialogs.text("id", "Map ID", "", 40), Dialogs.text("name", "Map name", "", 64),
                Dialogs.number("copies", "Arena copies", Math.clamp(plugin.getConfig().getInt("arenas.copies-per-map", 20), 1, 100), 1, 100, 1)),
                List.of(dialogs.button(player, "Save selection", (p, view) -> {
                    var result = plugin.getArenaManager().create(p, Dialogs.text(view, "id"), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    p.closeDialog();
                    result.whenComplete((map, failure) -> {
                        if (!p.isOnline()) return;
                        if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                        else { Dialogs.tell(p, map.getDisplayName() + " saved. Set both team spawns, then prepare its arenas."); edit(p, map.getId()); }
                    });
                }), dialogs.button(player, "Back", (p, view) -> open(p))));
    }
    public void edit(Player player, String id) {
        ArenaMap map = map(id);
        int ready = plugin.getArenaManager().available(id);
        String body = "ID: " + id + "\nReady arenas: " + ready + "/" + map.getCopies()
                + "\nTeam 1 spawn: " + (map.getTeam1Point() == null ? "not set" : "saved")
                + "\nTeam 2 spawn: " + (map.getTeam2Point() == null ? "not set" : "saved")
                + "\nStand at a spawn before choosing its button.";
        List<ActionButton> buttons = new ArrayList<>();
        for (String side : List.of("1", "2", "spectator")) {
            String label = side.equals("spectator") ? "Set spectator spawn" : "Set team " + side + " spawn";
            buttons.add(dialogs.button(player, label, (p, view) -> {
                plugin.getArenaManager().setSpawn(map(id), side, p.getLocation());
                p.closeDialog();
                Dialogs.tell(p, "Spawn saved.");
            }));
        }
        buttons.add(dialogs.button(player, "Name and copy count", (p, view) -> settings(p, id)));
        buttons.add(dialogs.button(player, "Save current blocks", (p, view) -> {
            p.closeDialog();
            plugin.getArenaManager().saveBlocks(map(id)).whenComplete((ignored, failure) -> {
                if (p.isOnline()) {
                    if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                    else Dialogs.tell(p, "Template saved. Prepare the arenas to apply it.");
                }
            });
        }));
        buttons.add(dialogs.button(player, "Prepare arenas", (p, view) -> {
            p.closeDialog();
            Dialogs.tell(p, "Preparing arena copies for " + map(id).getDisplayName() + ".");
            plugin.getArenaManager().prepare(map(id)).whenComplete((ignored, failure) -> {
                if (p.isOnline()) {
                    if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                    else Dialogs.tell(p, plugin.getArenaManager().available(id) + " arenas ready for " + map(id).getDisplayName() + ".");
                }
            });
        }));
        buttons.add(dialogs.button(player, "Refresh", (p, view) -> edit(p, id)));
        buttons.add(dialogs.button(player, "Back", (p, view) -> open(p)));
        dialogs.show(player, map.getDisplayName(), body, List.of(), buttons);
    }
    private void settings(Player player, String id) {
        ArenaMap map = map(id);
        dialogs.show(player, "Map settings", "Prepare the arenas after saving changes.", List.of(
                Dialogs.text("name", "Map name", map.getDisplayName(), 64),
                Dialogs.number("copies", "Arena copies", map.getCopies(), 1, 100, 1)),
                List.of(dialogs.button(player, "Save", (p, view) -> {
                    plugin.getArenaManager().configure(map(id), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    edit(p, id);
                }), dialogs.button(player, "Back", (p, view) -> edit(p, id))));
    }
}
