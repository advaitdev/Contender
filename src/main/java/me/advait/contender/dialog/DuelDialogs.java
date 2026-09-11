package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.duel.DuelMode;
import me.advait.contender.duel.DuelSetup;
import me.advait.contender.gui.duel.KitSelectGUI;
import me.advait.contender.tournament.RosterParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

public final class DuelDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    public DuelDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }
    public void open(Player player) {
        var maps = plugin.getMapManager().getMaps().stream().filter(m -> m.isComplete()).toList();
        var kits = new ArrayList<>(plugin.getKitManager().getKits());
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogs.button(player, "Edit kits", (p, view) -> {
            p.closeDialog();
            KitSelectGUI.open(p, plugin.getKitManager(), plugin.getDuelManager().createSetup(p.getUniqueId()));
        }));
        buttons.add(dialogs.button(player, "Maps", (p, view) -> new ArenaDialogs(plugin).open(p)));
        if (maps.isEmpty() || kits.isEmpty()) {
            dialogs.show(player, "Duel setup", "Save a map with both team spawns and a kit to set up a duel.", List.of(), buttons);
            return;
        }
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("map", Component.text("Map"), maps.stream().map(m -> Dialogs.option(m.getId(), m.getDisplayName()
                        + " (" + plugin.getArenaManager().available(m.getId()) + " ready)", m == maps.getFirst())).toList()).build(),
                DialogInput.singleOption("kit", Component.text("Kit"), kits.stream().map(k -> Dialogs.option(k.getId(), k.getDisplayName(), k == kits.getFirst())).toList()).build(),
                DialogInput.singleOption("mode", Component.text("Mode"), List.of(Dialogs.option("teams", "Two sides", true), Dialogs.option("ffa", "Free for all", false))).build(),
                Dialogs.number("rounds", "Best of", 3, 1, 15, 2), Dialogs.number("delay", "Inventory sorting (seconds)", 10, 5, 60, 1));
        buttons.addFirst(dialogs.button(player, "Choose players", (p, view) -> {
            DuelSetup setup = new DuelSetup(p.getUniqueId());
            setup.setSelectedMap(plugin.getMapManager().getMap(Dialogs.text(view, "map")));
            setup.setSelectedKit(plugin.getKitManager().getKit(Dialogs.text(view, "kit")));
            setup.setRounds(Dialogs.number(view, "rounds", 1, 15));
            setup.setPreRoundDelay(Dialogs.number(view, "delay", 5, 60));
            setup.setMode(Dialogs.text(view, "mode").equals("ffa") ? DuelMode.FFA : DuelMode.STANDARD);
            players(p, setup);
        }));
        dialogs.show(player, "Duel setup", "Each duel gets a separate arena copy.", inputs, buttons);
    }
    private void players(Player player, DuelSetup setup) {
        boolean ffa = setup.getMode() == DuelMode.FFA;
        List<DialogInput> inputs = ffa ? List.of() : List.of(Dialogs.text("team1", "Team 1 players", "", 1024), Dialogs.text("team2", "Team 2 players", "", 1024));
        dialogs.show(player, "Duel players", ffa ? "All available contestants who are not entered in the tournament will join."
                        : "Enter names separated by commas. Use one name on each side for a 1v1.", inputs,
                List.of(dialogs.button(player, "Start duel", (p, view) -> {
                    if (!ffa) {
                        setup.getTeam1().clearPlayers(); setup.getTeam2().clearPlayers();
                        for (var entry : parse(Dialogs.text(view, "team1"))) entry.players().forEach(setup.getTeam1()::addPlayer);
                        for (var entry : parse(Dialogs.text(view, "team2"))) entry.players().forEach(setup.getTeam2()::addPlayer);
                    }
                    plugin.getDuelManager().startDuel(setup);
                    p.closeDialog();
                    Dialogs.tell(p, "Duel started.");
                }), dialogs.button(player, "Back", (p, view) -> open(p))));
    }
    private List<me.advait.contender.tournament.TournamentEntry> parse(String names) {
        return RosterParser.parse(names, false, name -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) throw new IllegalArgumentException(name + " must be online to start a duel.");
            plugin.getDuelManager().requireEligible(player.getUniqueId());
            return new RosterParser.PlayerIdentity(player.getUniqueId(), player.getName());
        });
    }
    public void spectate(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        for (var duel : plugin.getDuelManager().getActiveDuels()) {
            if (!duel.getState().canAddSpectator()) continue;
            buttons.add(dialogs.button(player, duel.getTeam1().getName() + " vs " + duel.getTeam2().getName(), false, (p, view) -> {
                plugin.getDuelManager().spectate(p, duel); p.closeDialog();
            }));
        }
        buttons.add(dialogs.button(player, "Return to lobby", false, (p, view) -> { plugin.getDuelManager().leaveSpectating(p); p.closeDialog(); }));
        dialogs.show(player, "Spectate", "Choose a match to watch.", List.of(), buttons);
    }
}
