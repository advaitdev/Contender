package me.advait.contender.command;

import me.advait.contender.Contender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Emergency recovery works from the console as well as in game. */
public final class CancelAllCommand implements CommandExecutor {
    private final Contender plugin;
    public CancelAllCommand(Contender plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(Component.text("Usage: /cancelall", NamedTextColor.YELLOW));
            return true;
        }
        cancel(sender);
        return true;
    }

    public void cancel(CommandSender sender) {
        if (!sender.hasPermission("contender.master")) {
            sender.sendMessage(Component.text("You don't have permission to cancel events.", NamedTextColor.RED));
            return;
        }
        var failures = plugin.getMinigameManager().forceCancelAll();
        if (failures.isEmpty()) sender.sendMessage(Component.text("All events, duels, and votes cancelled.", NamedTextColor.GREEN));
        else sender.sendMessage(Component.text("Cancellation needs attention: " + String.join(", ", failures) + ". Check the server log.", NamedTextColor.RED));
        if (plugin.getServer().getOnlinePlayers().stream().anyMatch(p -> plugin.getMinigameManager().pendingReturn(p.getUniqueId()))) {
            sender.sendMessage(Component.text("Some players still need to return to the lobby. Their inventories are saved; returns will be retried.", NamedTextColor.YELLOW));
        }
    }
}
