package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.dialog.DuelDialogs;
import me.advait.contender.dialog.Dialogs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class DuelCommand implements CommandExecutor {

    private final Contender plugin;
    private final DuelManager duelManager;

    public DuelCommand(Contender plugin, DuelManager duelManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("contender.master")) {
            Dialogs.error(player, "You don't have permission to start duels.");
            return true;
        }

        if (plugin.getVoteManager().isVoteActive()) {
            Dialogs.error(player, "Wait for the vote to finish before starting a duel.");
            return true;
        }

        new DuelDialogs(plugin).open(player);
        return true;
    }
}
