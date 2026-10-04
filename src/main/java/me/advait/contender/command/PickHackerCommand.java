package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.dialog.Dialogs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public final class PickHackerCommand implements CommandExecutor, TabCompleter {
    private final Contender plugin;
    public PickHackerCommand(Contender plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("contender.master")) { sender.sendMessage(Component.text("You don't have permission to pick hackers.", NamedTextColor.RED)); return true; }
        if (args.length == 0) { sender.sendMessage("Usage: /pickhacker <name> [name ...] or /pickhacker --clear"); return true; }
        try {
            var players = new ArrayList<OfflinePlayer>();
            boolean clear = args.length == 1 && args[0].equalsIgnoreCase("--clear");
            if (!clear) for (String name : args) {
                OfflinePlayer target = plugin.getServer().getPlayerExact(name);
                if (target == null) target = plugin.getServer().getOfflinePlayerIfCached(name);
                if (target == null) target = plugin.getHackers().hackers().entrySet().stream().filter(entry -> entry.getValue().name().equalsIgnoreCase(name))
                        .findFirst().map(entry -> plugin.getServer().getOfflinePlayer(entry.getKey())).orElse(null);
                if (target == null) throw new IllegalArgumentException("Couldn't find " + name + ". They need to join the server once first.");
                players.add(target);
            }
            plugin.getHackers().pick(players);
            sender.sendMessage(Component.text(clear ? "Hacker selection cleared." : "Hackers selected: "
                    + String.join(", ", plugin.getHackers().hackers().values().stream().map(profile -> profile.name()).toList()) + ".", NamedTextColor.GREEN));
        } catch (RuntimeException failure) { sender.sendMessage(Component.text(Dialogs.message(failure), NamedTextColor.RED)); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("contender.master")) return List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        Set<String> used = new HashSet<>(Arrays.asList(args));
        var names = new ArrayList<String>();
        if (args.length <= 1) names.add("--clear");
        for (Player player : plugin.getServer().getOnlinePlayers()) if (plugin.getRoleManager().isContestant(player.getUniqueId()) && !used.contains(player.getName())) names.add(player.getName());
        // Hackers who left or were voted out can still be kept.
        for (var profile : plugin.getHackers().hackers().values()) if (!used.contains(profile.name()) && !names.contains(profile.name())) names.add(profile.name());
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
