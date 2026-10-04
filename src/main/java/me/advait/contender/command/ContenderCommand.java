package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.duel.Duel;
import me.advait.contender.map.ArenaMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /contender status | reload | voice [player] */
public final class ContenderCommand implements TabExecutor {
    private final Contender plugin;

    public ContenderCommand(Contender plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "voice" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player player ? player : null;
                if (target == null) { Msg.error(sender, "Name an online player."); return true; }
                Msg.info(sender, "Voice for " + target.getName() + ":");
                for (String line : plugin.getVoice().describe(target)) Msg.hint(sender, "  " + line);
            }
            default -> Msg.hint(sender, "Use /contender status, /contender reload or /contender voice [player].");
        }
        return true;
    }

    private void status(CommandSender sender) {
        Msg.info(sender, "Contender " + plugin.getPluginMeta().getVersion());
        var stage = plugin.getStages().current();
        Msg.hint(sender, "  Event: " + (stage == null ? "none" : stage.name() + " (" + stage.kind() + ", " + stage.statusText() + ")"));
        Msg.hint(sender, "  Matches running: " + plugin.getDuels().duels().size());
        for (Duel duel : plugin.getDuels().duels()) {
            Msg.hint(sender, "    " + duel.displayName() + ": " + String.join(" vs ", duel.teams().stream().map(t -> t.name() + " " + t.score()).toList())
                    + " · " + duel.phase().name().toLowerCase(Locale.ROOT) + " · round " + duel.round());
        }
        for (ArenaMap map : plugin.getMapManager().getMaps()) Msg.hint(sender, "  Map " + map.getDisplayName() + ": " + plugin.getArenas().readiness(map.getId()));
        Msg.hint(sender, "  Vote: " + (plugin.getVotes().isActive() ? "running" : plugin.getVotes().revealing() ? "revealing" : "none"));
        Msg.hint(sender, "  Hackers: " + plugin.getHackers().hackers().size() + " · mode " + plugin.getHackers().mode().name().toLowerCase(Locale.ROOT)
                + (plugin.getHackers().planActive() ? " · plan on" : ""));
        Msg.hint(sender, "  Hackers can sabotage: " + (plugin.getSabotage().enabled() ? "yes" : "no, only directors") + ", running: "
                + (plugin.getSabotage().active().isEmpty() ? "none" : String.join(", ", plugin.getSabotage().active().keySet())));
        Msg.hint(sender, "  Interview: " + (plugin.getInterviews().active() ? "in progress" : "none"));
    }

    private void reload(CommandSender sender) {
        if (plugin.getStages().busy() || !plugin.getDuels().duels().isEmpty()) {
            Msg.error(sender, "Finish or cancel the current event and matches before reloading.");
            return;
        }
        plugin.reloadConfig();
        plugin.getKitManager().loadKits();
        plugin.getMapManager().loadMaps();
        plugin.loadWorlds();
        for (ArenaMap map : plugin.getMapManager().getMaps()) plugin.getArenas().rebuildPool(map);
        plugin.getNameTagManager().refresh();
        plugin.getStages().refreshDisplays();
        plugin.getBoard().rebuild();
        Msg.success(sender, "Reloaded settings, kits and maps.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "reload", "voice").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("voice")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
