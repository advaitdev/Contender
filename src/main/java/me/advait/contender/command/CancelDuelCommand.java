package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Cancels without a score; a tournament pairing stays queued while the tournament is paused. */
public final class CancelDuelCommand implements TabExecutor {
    private final Contender plugin;
    public CancelDuelCommand(Contender plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("contender.master")) {
            sender.sendMessage(Component.text("You don't have permission to cancel duels.", NamedTextColor.RED));
            return true;
        }
        if (args.length > 1 || args.length == 0 && !(sender instanceof Player)) {
            sender.sendMessage(Component.text("Usage: /cancelduel <player>", NamedTextColor.YELLOW)); return true;
        }
        Player target = args.length == 0 ? (Player) sender : plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("That player is not online.", NamedTextColor.RED)); return true;
        }
        Duel duel = plugin.getDuelManager().getDuel(target);
        if (duel == null || duel.isFinished()) {
            sender.sendMessage(Component.text(target.getName() + " is not in a duel.", NamedTextColor.RED)); return true;
        }
        boolean tournament = plugin.getTournamentManager().cancelDuel(duel);
        sender.sendMessage(Component.text("Duel cancelled. No result was recorded.", NamedTextColor.GREEN));
        if (tournament) sender.sendMessage(Component.text("Tournament paused. The match can be replayed when you resume it.", NamedTextColor.YELLOW));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("contender.master") || args.length != 1) return List.of();
        String start = args[0].toLowerCase(Locale.ROOT);
        return plugin.getServer().getOnlinePlayers().stream().filter(player -> plugin.getDuelManager().getDuel(player) != null)
                .map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
