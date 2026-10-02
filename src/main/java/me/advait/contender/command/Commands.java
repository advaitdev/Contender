package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.dialog.*;
import me.advait.contender.duel.Duel;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.vote.VoteService;
import me.advait.contender.vote.VoteSession;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/** Registers every command. Metadata (descriptions, permissions) lives in plugin.yml. */
public final class Commands {
    private final Contender plugin;

    public Commands(Contender plugin) { this.plugin = plugin; }

    public void register() {
        PaperCommands commands = new PaperCommands(plugin);
        dialog(commands, "tournament", true, p -> new TournamentDialogs(plugin).open(p));
        dialog(commands, "duel", true, p -> new DuelDialogs(plugin).open(p));
        dialog(commands, "arena", true, p -> new ArenaDialogs(plugin).open(p));
        dialog(commands, "spectate", false, p -> new SpectateDialogs(plugin).open(p));
        dialog(commands, "bracket", false, p -> new BracketDialogs(plugin).open(p));
        dialog(commands, "options", false, p -> new SettingsDialogs(plugin).open(p));
        dialog(commands, "hacks", false, p -> new HackerDialogs(plugin).open(p));
        dialog(commands, "hackers", true, p -> new HackerAdminDialogs(plugin).open(p));
        dialog(commands, "sabotage", false, p -> new SabotageDialogs(plugin).open(p));
        dialog(commands, "lobby", false, p -> lobby(plugin, p));
        dialog(commands, "setlobby", true, p -> { plugin.getLobby().setLocation(p.getLocation()); Dialogs.tell(p, "Lobby set here."); });
        dialog(commands, "setinterviewerposition", true, p -> { plugin.getInterviews().setPosition("interviewer", p.getLocation()); Dialogs.tell(p, "Your interview spot is saved."); });
        dialog(commands, "setintervieweeposition", true, p -> { plugin.getInterviews().setPosition("interviewee", p.getLocation()); Dialogs.tell(p, "The interviewee's spot is saved."); });

        RoleCommand role = new RoleCommand(plugin);
        set(commands, "role", role, role);
        set(commands, "tier", new TierCommand(plugin), null);
        PickHackerCommand pick = new PickHackerCommand(plugin);
        set(commands, "pickhacker", pick, pick);

        set(commands, "vote", (sender, command, label, args) -> { vote(sender, args); return true; }, null);
        set(commands, "startvote", (sender, command, label, args) -> { startVote(sender, args); return true; }, null);
        set(commands, "endvote", (sender, command, label, args) -> {
            if (!plugin.getVotes().isActive()) Msg.error(sender, "No vote is running.");
            else { plugin.getVotes().end(); Msg.success(sender, "Voting closed."); }
            return true;
        }, null);
        set(commands, "votetimer", (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { Msg.error(sender, "Use this in game."); return true; }
            String action = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "remove" -> {
                    if (plugin.getVotes().removeNearestTimer(player)) Msg.success(player, "Removed the nearest vote timer.");
                    else Msg.error(player, "No vote timer within 12 blocks.");
                }
                case "clear" -> { plugin.getVotes().removeTimers(); Msg.success(player, "All vote timers removed."); }
                default -> {
                    float size = 4f;
                    if (!action.isEmpty()) {
                        try { size = Float.parseFloat(action); }
                        catch (NumberFormatException invalid) { Msg.error(player, "Use /votetimer [size 1-10], /votetimer remove or /votetimer clear."); return true; }
                    }
                    boolean wall = plugin.getVotes().placeTimer(player, size);
                    Msg.success(player, (wall ? "Vote timer hung on the wall." : "Vote timer placed in front of you.")
                            + " It shows during votes. Timers: " + plugin.getVotes().timerCount() + ".");
                }
            }
            return true;
        }, (sender, command, alias, args) -> args.length == 1 ? List.of("remove", "clear", "4", "6", "8") : List.of());
        for (var room : me.advait.contender.vote.VoteService.Room.values()) {
            String name = room == me.advait.contender.vote.VoteService.Room.VOTING ? "setvotingroom" : "setjudgeroom";
            String who = room == me.advait.contender.vote.VoteService.Room.VOTING ? "Contestants" : "Everyone who isn't voting";
            set(commands, name, (sender, command, label, args) -> {
                if (!(sender instanceof Player player)) { Msg.error(sender, "Use this in game."); return true; }
                if (args.length == 1 && args[0].equalsIgnoreCase("remove")) {
                    plugin.getVotes().clearRoom(room);
                    Msg.success(player, "The " + room.label + " was removed.");
                } else {
                    plugin.getVotes().setRoom(room, player.getLocation());
                    Msg.success(player, "The " + room.label + " is set here. " + who + " will be sent here when a vote starts, facing the way you face now.");
                }
                return true;
            }, (sender, command, alias, args) -> args.length == 1 ? List.of("remove") : List.of());
        }
        set(commands, "setvotestage", (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { Msg.error(sender, "Use this in game."); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("remove")) { plugin.getVotes().clearStage(); Msg.success(player, "Vote stage removed."); }
            else { plugin.getVotes().setStage(player.getLocation()); Msg.success(player, "Vote stage set. Contestants will line up here for the results, facing the way you face now."); }
            return true;
        }, null);

        TabCompleter online = (sender, command, alias, args) -> args.length == 1 ? names(args[0]) : List.of();
        set(commands, "interview", (sender, command, label, args) -> {
            if (args.length != 1) { Msg.error(sender, "Use /interview <player>."); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { Msg.error(sender, args[0] + " is not online."); return true; }
            plugin.getInterviews().start(target, sender instanceof Player player ? player : null);
            Msg.success(sender, "Interviewing " + target.getName() + ". Use /uninterview to send everyone back.");
            return true;
        }, online);
        set(commands, "uninterview", (sender, command, label, args) -> {
            plugin.getInterviews().end();
            Msg.success(sender, "Interview over. Everyone is back where they were.");
            return true;
        }, null);

        set(commands, "endduel", (sender, command, label, args) -> {
            Duel duel = targetDuel(sender, args);
            if (duel != null) { duel.endNow(); Msg.success(sender, "Ending the match with the current score."); }
            return true;
        }, online);
        set(commands, "cancelduel", (sender, command, label, args) -> {
            Duel duel = targetDuel(sender, args);
            if (duel == null) return true;
            boolean tournament = plugin.getTournaments().cancelMatch(duel);
            Msg.success(sender, "Match cancelled. No result was recorded.");
            if (tournament) Msg.warn(sender, "The tournament is paused; the match goes back in the queue. Resume it from /tournament.");
            return true;
        }, online);
        set(commands, "cancelall", (sender, command, label, args) -> { cancelAll(plugin, sender); return true; }, null);
        ContenderCommand admin = new ContenderCommand(plugin);
        set(commands, "contender", admin, admin);
        commands.register();
    }

    private void dialog(PaperCommands commands, String name, boolean admin, java.util.function.Consumer<Player> open) {
        commands.command(name).setExecutor(new DialogCommand(admin, open));
    }

    private void set(PaperCommands commands, String name, CommandExecutor executor, TabCompleter completer) {
        CommandExecutor safe = (sender, command, label, args) -> {
            try { return executor.onCommand(sender, command, label, args); }
            catch (RuntimeException failure) {
                Msg.error(sender, Msg.reason(failure));
                if (!(failure instanceof IllegalStateException) && !(failure instanceof IllegalArgumentException)) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "/" + name + " failed", failure);
                }
                return true;
            }
        };
        commands.command(name).setExecutor(safe);
        if (completer != null) commands.command(name).setTabCompleter(completer);
    }

    private static List<String> names(String prefix) {
        String start = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(start)).sorted().toList();
    }

    /** Leaves whatever the player is watching, or returns them to the lobby. Players in a game must finish first. */
    public static void lobby(Contender plugin, Player player) {
        var registry = plugin.getRegistry();
        if (registry.isWatching(player.getUniqueId())) { plugin.getSpectate().stop(player.getUniqueId(), true); return; }
        var owner = registry.owner(player.getUniqueId());
        if (owner instanceof Minigame game && !game.isPlaying(player.getUniqueId())) {
            // Knocked out of a minigame: they may leave early.
            game.withdraw(player.getUniqueId());
            return;
        }
        if (owner != null) throw new IllegalStateException("Finish " + owner.displayName() + " first.");
        if (plugin.getSnapshots().has(player.getUniqueId())) { plugin.getSnapshots().restoreToLobby(player); return; }
        if (!plugin.getLobby().send(player)) throw new IllegalStateException("The lobby isn't available right now.");
    }

    public static void cancelAll(Contender plugin, CommandSender sender) {
        List<String> failures = new ArrayList<>();
        try { if (plugin.getTournaments().current() != null && !plugin.getTournaments().current().isCancelled()
                && !plugin.getTournaments().current().isComplete()) plugin.getTournaments().cancel(); }
        catch (RuntimeException failure) { failures.add("round robin"); plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cancel all: tournament", failure); }
        Minigame game = plugin.getMinigames().current();
        if (game != null && !game.finished()) {
            try { game.cancel("A director stopped everything."); }
            catch (RuntimeException failure) { failures.add(game.name()); plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cancel all: minigame", failure); }
        }
        try { plugin.getVotes().cancel(); }
        catch (RuntimeException failure) { failures.add("vote"); plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cancel all: vote", failure); }
        failures.addAll(plugin.getRegistry().forceStopAll("A director stopped everything."));
        try { plugin.getSabotage().endAll(false); }
        catch (RuntimeException failure) { failures.add("sabotages"); }
        plugin.getStages().refreshDisplays();
        if (failures.isEmpty()) Msg.success(sender, "Everything stopped. Players are back in the lobby.");
        else Msg.error(sender, "Stopped, but these need a look in the server log: " + String.join(", ", failures) + ".");
    }

    private Duel targetDuel(CommandSender sender, String[] args) {
        Player target = args.length == 1 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player player ? player : null;
        if (target == null) { Msg.error(sender, args.length == 1 ? args[0] + " is not online." : "Name a player."); return null; }
        Duel duel = plugin.getDuels().duelOf(target.getUniqueId());
        if (duel == null && plugin.getSpectate().target(target.getUniqueId()) instanceof Duel watched) duel = watched;
        if (duel == null) Msg.error(sender, target.getName() + " is not in a match.");
        return duel;
    }

    private void vote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { Msg.error(sender, "Use this in game."); return; }
        if (args.length == 0) { new VoteDialogs(plugin).open(player); return; }
        VoteSession session = plugin.getVotes().session();
        if (session == null) { Msg.error(player, "No vote is running."); return; }
        // By name; a number still works for anyone using the old numbering.
        VoteSession.Candidate candidate = session.candidates().stream().filter(c -> c.name().equalsIgnoreCase(args[0])).findFirst().orElse(null);
        if (candidate == null && args[0].matches("\\d+")) candidate = session.byNumber(Integer.parseInt(args[0]));
        if (candidate == null) { Msg.error(player, args[0] + " isn't in this vote. Use /vote to see who is."); return; }
        plugin.getVotes().vote(player, candidate);
        Msg.success(player, "You voted for " + candidate.name() + ".");
    }

    private void startVote(CommandSender sender, String[] args) {
        if (args.length == 0 && sender instanceof Player player) { new VoteDialogs(plugin).start(player); return; }
        if (args.length == 0) { Msg.error(sender, "Use /startvote <seconds> [live] [quick] [spectate|keep] [stay|nomove]."); return; }
        int seconds;
        try { seconds = Integer.parseInt(args[0]); }
        catch (NumberFormatException invalid) { Msg.error(sender, "Use /startvote <seconds>."); return; }
        Set<String> flags = new HashSet<>();
        for (int i = 1; i < args.length; i++) flags.add(args[i].toLowerCase(Locale.ROOT));
        VoteService.RoomMode rooms = flags.contains("nomove") ? VoteService.RoomMode.OFF : flags.contains("stay") ? VoteService.RoomMode.STAY : VoteService.RoomMode.RETURN;
        // The voted-out player dies and spectates unless told otherwise.
        VoteService.Elimination elimination = flags.contains("keep") ? VoteService.Elimination.KEEP
                : flags.contains("spectate") || flags.contains("eliminate") ? VoteService.Elimination.SPECTATE : VoteService.Elimination.KILL;
        plugin.getVotes().start(new VoteService.Options(seconds, flags.contains("live"), !flags.contains("quick"), elimination, rooms));
        Msg.success(sender, "Vote started for " + seconds + " seconds.");
    }
}
