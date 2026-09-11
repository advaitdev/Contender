package me.advait.contender.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import me.advait.contender.Contender;
import me.advait.contender.player.PlayerIdentityResolver;
import me.advait.contender.tournament.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.*;

public final class TournamentDialogs {
    private final Contender plugin;
    private final Dialogs dialogs;
    private final Map<UUID, UUID> rosterViews = new HashMap<>();
    private record Draft(String name, String map, String kit, boolean teams, boolean waitForRound, int bestOf) { }
    public TournamentDialogs(Contender plugin) { this.plugin = plugin; dialogs = new Dialogs(plugin); }

    public void open(Player player) {
        rosterViews.remove(player.getUniqueId());
        var manager = plugin.getTournamentManager();
        Tournament tournament = manager.current();
        List<ActionButton> buttons = new ArrayList<>();
        String body = "Create a round-robin tournament. Every entry plays every other entry once.";
        if (tournament != null) {
            var map = plugin.getMapManager().getMap(tournament.mapId());
            var kit = plugin.getKitManager().getKit(tournament.kitId());
            long finished = tournament.matches().stream().filter(m -> m.status() == TournamentMatch.Status.FINISHED).count();
            body = tournament.name() + "\n" + tournament.statusText() + " | " + finished + "/" + tournament.matches().size() + " matches finished"
                    + "\nMap: " + (map == null ? tournament.mapId() : map.getDisplayName())
                    + "\nKit: " + (kit == null ? tournament.kitId() : kit.getDisplayName())
                    + "\nSchedule: " + (tournament.waitForRound() ? "wait for each tournament round" : "start matches when players are free")
                    + (tournament.isRunning() ? "\n" + manager.waitingReason() : "");
            if (!tournament.isComplete() && !tournament.isCancelled()) {
                buttons.add(dialogs.button(player, tournament.isRunning() ? "Pause new matches" : "Start / resume", (p, view) -> {
                    current(tournament.id());
                    if (tournament.isRunning()) manager.pause(); else manager.resume();
                    open(p);
                }));
                buttons.add(dialogs.button(player, "Cancel tournament", (p, view) -> confirmCancel(p, tournament.id())));
            }
            buttons.add(dialogs.button(player, "Matches", (p, view) -> matches(p, tournament.id(), 0)));
        }
        if (tournament == null || tournament.isComplete() || tournament.isCancelled()) buttons.add(dialogs.button(player, "New tournament", (p, view) -> create(p)));
        buttons.add(dialogs.button(player, "Place board here", (p, view) -> {
            manager.board().place(p);
            manager.board().update(manager.current(), manager.playing());
            p.closeDialog();
            Dialogs.tell(p, "Tournament board placed in front of you.");
        }));
        buttons.add(dialogs.button(player, "Remove board", (p, view) -> { manager.board().remove(); open(p); }));
        buttons.add(dialogs.button(player, "Refresh", (p, view) -> open(p)));
        dialogs.show(player, "Tournament", body, List.of(), buttons);
    }
    private Tournament current(UUID id) {
        Tournament tournament = plugin.getTournamentManager().current();
        if (tournament == null || !tournament.id().equals(id)) throw new IllegalStateException("That tournament is no longer selected.");
        return tournament;
    }
    private void create(Player player) {
        rosterViews.remove(player.getUniqueId());
        var maps = plugin.getMapManager().getMaps().stream().filter(m -> m.isComplete()).toList();
        var kits = new ArrayList<>(plugin.getKitManager().getKits());
        if (maps.isEmpty() || kits.isEmpty()) throw new IllegalStateException("Save a map with both spawns and a kit before creating a tournament.");
        List<DialogInput> inputs = List.of(Dialogs.text("name", "Tournament name", "Tournament", 64),
                DialogInput.singleOption("map", Component.text("Map"), maps.stream().map(m -> Dialogs.option(m.getId(), m.getDisplayName(), m == maps.getFirst())).toList()).build(),
                DialogInput.singleOption("kit", Component.text("Kit"), kits.stream().map(k -> Dialogs.option(k.getId(), k.getDisplayName(), k == kits.getFirst())).toList()).build(),
                DialogInput.singleOption("roster", Component.text("Entries"), List.of(Dialogs.option("solo", "Players (1v1)", true), Dialogs.option("teams", "Teams", false))).build(),
                Dialogs.number("bestof", "Best of", 3, 1, 15, 2),
                DialogInput.singleOption("schedule", Component.text("Schedule"), List.of(Dialogs.option("free", "Start available matches", true), Dialogs.option("rounds", "Wait for each round", false))).build());
        dialogs.show(player, "Tournament setup", "Choose the match settings. You can change the round count of any waiting match later.", inputs,
                List.of(dialogs.button(player, "Choose players", (p, view) -> roster(p, new Draft(Dialogs.text(view, "name"), Dialogs.text(view, "map"),
                        Dialogs.text(view, "kit"), Dialogs.text(view, "roster").equals("teams"), Dialogs.text(view, "schedule").equals("rounds"), Dialogs.number(view, "bestof", 1, 15)))),
                        dialogs.button(player, "Back", (p, view) -> open(p))));
    }
    private void roster(Player player, Draft draft) {
        String initial = draft.teams() ? "" : String.join(", ", Bukkit.getOnlinePlayers().stream()
                .filter(p -> plugin.getDuelManager().isEligible(p.getUniqueId())).map(Player::getName).toList());
        roster(player, draft, initial, 10, 20, null);
    }
    private void roster(Player player, Draft draft, String initial, int delay, int parallel, String error) {
        UUID form = UUID.randomUUID();
        rosterViews.put(player.getUniqueId(), form);
        String instructions = draft.teams() ? "One team per line. For example:\nRed: Alice, Bob\nBlue: Charlie, Dana"
                : "Enter player names, separated by commas or new lines.";
        instructions += "\nPlayers can be offline, even if they haven't joined before. Matches wait until both sides are online.";
        if (error != null) instructions = error + "\n\n" + instructions;
        List<DialogInput> inputs = List.of(
                DialogInput.text("players", Component.text(draft.teams() ? "Teams" : "Players")).initial(initial).maxLength(4096)
                        .multiline(TextDialogInput.MultilineOptions.create(64, 100)).width(350).build(),
                Dialogs.number("delay", "Inventory sorting (seconds)", delay, 5, 60, 1),
                Dialogs.number("parallel", "Simultaneous matches", parallel, 1, 100, 1));
        dialogs.show(player, "Tournament entries", instructions, inputs,
                List.of(dialogs.button(player, "Create tournament", (p, view) -> {
                    if (!form.equals(rosterViews.get(p.getUniqueId()))) return;
                    // Read the response now; profile completion may arrive on another thread.
                    lookupRoster(p, form, draft, Dialogs.text(view, "players"),
                            Dialogs.number(view, "delay", 5, 60), Dialogs.number(view, "parallel", 1, 100));
                }), dialogs.button(player, "Back", (p, view) -> create(p))));
    }
    private void lookupRoster(Player player, UUID form, Draft draft, String names, int delay, int parallel) {
        var result = RosterParser.parseAsync(names, draft.teams(), PlayerIdentityResolver::resolve);
        UUID owner = player.getUniqueId();
        UUID request = UUID.randomUUID();
        if (!rosterViews.replace(owner, form, request)) return;
        Tournament previous = plugin.getTournamentManager().current();
        dialogs.show(player, "Looking up players", "The tournament will be saved once every name has been checked.", List.of(),
                List.of(dialogs.button(player, "Cancel lookup", (p, view) -> {
                    if (rosterViews.remove(owner, request)) roster(p, draft, names, delay, parallel, null);
                })));
        result.whenComplete((entries, failure) -> {
            if (!plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!rosterViews.remove(owner, request) || !plugin.isEnabled() || !player.isOnline()) return;
                    if (!player.hasPermission("contender.master")) {
                        player.closeDialog();
                        Dialogs.error(player, "You don't have permission to create a tournament.");
                        return;
                    }
                    if (failure != null) {
                        roster(player, draft, names, delay, parallel, Dialogs.message(failure));
                        return;
                    }
                    try {
                        if (plugin.getTournamentManager().current() != previous) {
                            throw new IllegalStateException("The selected tournament changed. Open /tournament to check it.");
                        }
                        Tournament tournament = new Tournament(UUID.randomUUID(), draft.name(), draft.map(), draft.kit(), entries,
                                draft.teams(), draft.waitForRound(), draft.bestOf(), delay, parallel);
                        // Creation rechecks roles and deceased status after the lookup completes.
                        plugin.getTournamentManager().create(tournament);
                        open(player);
                        Dialogs.tell(player, "Tournament saved. Start it when you're ready.");
                    } catch (Exception error) {
                        roster(player, draft, names, delay, parallel, Dialogs.message(error));
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // The plugin stopped between the enabled check and scheduling the callback.
            }
        });
    }
    private void confirmCancel(Player player, UUID id) {
        dialogs.show(player, "Cancel tournament?", "This ends its active matches and stops the remaining schedule.", List.of(),
                List.of(dialogs.button(player, "Cancel tournament", (p, view) -> { current(id); plugin.getTournamentManager().cancel(); open(p); }),
                        dialogs.button(player, "Keep playing", (p, view) -> open(p))));
    }
    private void matches(Player player, UUID id, int page) {
        Tournament tournament = current(id);
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = page * 8; i < Math.min(page * 8 + 8, tournament.matches().size()); i++) {
            TournamentMatch match = tournament.matches().get(i);
            String label = "#" + match.number() + " " + tournament.entries().get(match.first()).name() + " vs " + tournament.entries().get(match.second()).name();
            buttons.add(dialogs.button(player, label, (p, view) -> match(p, id, match.number(), page)));
        }
        if (page > 0) buttons.add(dialogs.button(player, "Previous page", (p, view) -> matches(p, id, page - 1)));
        if ((page + 1) * 8 < tournament.matches().size()) buttons.add(dialogs.button(player, "Next page", (p, view) -> matches(p, id, page + 1)));
        buttons.add(dialogs.button(player, "Back", (p, view) -> open(p)));
        dialogs.show(player, "Matches", "Page " + (page + 1) + ". Select a match to view its score or change its settings.", List.of(), buttons);
    }
    private void match(Player player, UUID id, int number, int page) {
        Tournament tournament = current(id);
        TournamentMatch match = tournament.matches().get(number - 1);
        String first = tournament.entries().get(match.first()).name();
        String second = tournament.entries().get(match.second()).name();
        List<ActionButton> buttons = new ArrayList<>();
        List<DialogInput> inputs = new ArrayList<>();
        String body = first + " vs " + second + "\nTournament round " + match.round();
        if (match.result() != null) body += "\nScore: " + match.result().team1Score() + "-" + match.result().team2Score();
        else body += "\n" + (match.status() == TournamentMatch.Status.PLAYING ? "Playing" : "Waiting") + ", best of " + match.bestOf();
        if (match.status() == TournamentMatch.Status.WAITING && !tournament.isCancelled()) {
            inputs.add(Dialogs.number("bestof", "Best of", match.bestOf(), 1, 15, 2));
            buttons.add(dialogs.button(player, "Save round count", (p, view) -> {
                current(id); plugin.getTournamentManager().setBestOf(match, Dialogs.number(view, "bestof", 1, 15)); match(p, id, number, page);
            }));
            buttons.add(dialogs.button(player, "Award " + first + " the win", (p, view) -> confirmAward(p, id, match, 1, page)));
            buttons.add(dialogs.button(player, "Award " + second + " the win", (p, view) -> confirmAward(p, id, match, 2, page)));
        }
        var duel = plugin.getTournamentManager().playing().get(number);
        if (duel != null) buttons.add(dialogs.button(player, "Spectate", (p, view) -> {
            current(id); plugin.getDuelManager().spectate(p, duel); p.closeDialog();
        }));
        buttons.add(dialogs.button(player, "Back", (p, view) -> matches(p, id, page)));
        dialogs.show(player, "Match #" + number, body, inputs, buttons);
    }
    private void confirmAward(Player player, UUID id, TournamentMatch match, int side, int page) {
        Tournament tournament = current(id);
        String name = tournament.entries().get(side == 1 ? match.first() : match.second()).name();
        dialogs.show(player, "Award match?", name + " will win match #" + match.number() + " by forfeit.", List.of(),
                List.of(dialogs.button(player, "Award win", (p, view) -> {
                    current(id); plugin.getTournamentManager().award(match, side); matches(p, id, page);
                }), dialogs.button(player, "Back", (p, view) -> match(p, id, match.number(), page))));
    }
}
