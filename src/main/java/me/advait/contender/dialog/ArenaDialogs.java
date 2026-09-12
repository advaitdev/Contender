package me.advait.contender.dialog;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
        buttons.add(dialogs.button(player, DialogIcon.MAP, "Create map", (p, view) -> create(p)));
        Dialogs.navigationRow(buttons,
                page > 0 ? dialogs.button(player, DialogIcon.BACK, "Previous page", (p, view) -> open(p, page - 1)) : null,
                (page + 1) * 8 < maps.size() ? dialogs.button(player, DialogIcon.NEXT, "Next page", (p, view) -> open(p, page + 1)) : null);
        dialogs.show(player, "Maps", DialogText.paragraphs(
                DialogText.muted("Select a map to set its spawns or prepare arena copies."),
                DialogText.muted("Creating a map?\nSelect both corners with the WorldEdit wand first."),
                DialogText.page(page + 1, Math.max(1, (maps.size() + 7) / 8))), List.of(), buttons);
    }
    private ArenaMap map(String id) {
        ArenaMap map = plugin.getMapManager().getMap(id);
        if (map == null) throw new IllegalArgumentException("That map no longer exists.");
        return map;
    }
    public void create(Player player) {
        dialogs.show(player, "Create map", DialogText.paragraphs(
                DialogText.muted("Save the selected blocks and entities as a map."),
                DialogText.lines(DialogText.detail("Map ID", "mines", NamedTextColor.AQUA),
                        DialogText.muted("A unique short name. Use lowercase letters, numbers, - or _.")),
                DialogText.lines(DialogText.detail("Map name", "The Mines", NamedTextColor.AQUA),
                        DialogText.muted("The name shown when choosing a map."))), List.of(
                Dialogs.text(MAP_ID_INPUT, "Map ID", "", 40), Dialogs.text("name", "Map name", "", 64),
                Dialogs.number("copies", "Arena copies", Math.clamp(plugin.getConfig().getInt("arenas.copies-per-map", 20), 1, 100), 1, 100, 1)),
                List.of(dialogs.button(player, DialogIcon.SAVE, "Save selection", (p, view) -> {
                    var result = plugin.getArenaManager().create(p, Dialogs.text(view, MAP_ID_INPUT), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    p.closeDialog();
                    result.whenComplete((map, failure) -> {
                        if (!p.isOnline()) return;
                        if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                        else { Dialogs.tell(p, map.getDisplayName() + " saved. Set both team spawns, then prepare its arenas."); edit(p, map.getId()); }
                    });
                }), dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> open(p))));
    }
    public void edit(Player player, String id) {
        ArenaMap map = map(id);
        int ready = plugin.getArenaManager().available(id);
        Component body = DialogText.paragraphs(
                DialogText.muted("ID: " + id),
                DialogText.detail("Ready arenas", ready + " / " + map.getCopies(),
                        ready == map.getCopies() ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                DialogText.lines(
                        DialogText.detail("Team 1 spawn", map.getTeam1Point() == null ? "Not set" : "Saved",
                                map.getTeam1Point() == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
                        DialogText.detail("Team 2 spawn", map.getTeam2Point() == null ? "Not set" : "Saved",
                                map.getTeam2Point() == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
                        DialogText.detail("Spectator spawn", map.getSpectatorPoint() == null ? "Uses team 1" : "Saved",
                                map.getSpectatorPoint() == null ? NamedTextColor.GRAY : NamedTextColor.GREEN)),
                DialogText.muted("Stand at a spawn, then choose its button below."));
        List<ActionButton> buttons = new ArrayList<>();
        for (String side : List.of("1", "2", "spectator")) {
            String label = side.equals("spectator") ? "Set spectator spawn" : "Set team " + side + " spawn";
            buttons.add(dialogs.button(player, DialogIcon.SPAWN, label, (p, view) -> {
                plugin.getArenaManager().setSpawn(map(id), side, p.getLocation());
                p.closeDialog();
                Dialogs.tell(p, "Spawn saved.");
            }));
        }
        buttons.add(dialogs.button(player, DialogIcon.NAME, "Name and copy count", (p, view) -> settings(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.SAVE, "Save current blocks", (p, view) -> {
            p.closeDialog();
            plugin.getArenaManager().saveBlocks(map(id)).whenComplete((ignored, failure) -> {
                if (p.isOnline()) {
                    if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                    else Dialogs.tell(p, "Template saved. Prepare the arenas to apply it.");
                }
            });
        }));
        buttons.add(dialogs.button(player, DialogIcon.MAP, "Prepare arenas", (p, view) -> {
            p.closeDialog();
            Dialogs.tell(p, "Preparing arena copies for " + map(id).getDisplayName() + ".");
            plugin.getArenaManager().prepare(map(id)).whenComplete((ignored, failure) -> {
                if (p.isOnline()) {
                    if (failure != null) Dialogs.error(p, Dialogs.message(failure));
                    else Dialogs.tell(p, plugin.getArenaManager().available(id) + " arenas ready for " + map(id).getDisplayName() + ".");
                }
            });
        }));
        buttons.add(dialogs.button(player, DialogIcon.REFRESH, "Refresh", (p, view) -> edit(p, id)));
        buttons.add(dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> open(p)));
        dialogs.show(player, map.getDisplayName(), body, List.of(), buttons);
    }
    private void settings(Player player, String id) {
        ArenaMap map = map(id);
        dialogs.show(player, "Map settings", DialogText.paragraphs(DialogText.muted("ID: " + id),
                Component.text("Prepare the arenas again after saving changes.", NamedTextColor.YELLOW)), List.of(
                Dialogs.text("name", "Map name", map.getDisplayName(), 64),
                Dialogs.number("copies", "Arena copies", map.getCopies(), 1, 100, 1)),
                List.of(dialogs.button(player, DialogIcon.SAVE, "Save", (p, view) -> {
                    plugin.getArenaManager().configure(map(id), Dialogs.text(view, "name"), Dialogs.number(view, "copies", 1, 100));
                    edit(p, id);
                }), dialogs.button(player, DialogIcon.BACK, "Back", (p, view) -> edit(p, id))));
    }
}
