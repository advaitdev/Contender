package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.dialog.VoteDialogs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class VoteCommand implements CommandExecutor {
    private final Contender plugin;
    public VoteCommand(Contender plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                        @NotNull String label, String[] args) {
        if (sender instanceof Player player) new VoteDialogs(plugin).open(player);
        else sender.sendMessage("This command can only be used by players.");
        return true;
    }
}
