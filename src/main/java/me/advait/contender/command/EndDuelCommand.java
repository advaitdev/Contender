package me.advait.contender.command;

import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class EndDuelCommand implements CommandExecutor {

    private final DuelManager duelManager;

    public EndDuelCommand(DuelManager duelManager) {
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
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">You don't have permission to do this!</color>");
            return true;
        }

        Duel duel = duelManager.getDuel(player);
        if (duel == null) {
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">You are not in a duel!</color>");
            return true;
        }

        duel.endDuel();
        return true;
    }
}
