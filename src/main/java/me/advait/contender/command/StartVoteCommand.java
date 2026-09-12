package me.advait.contender.command;

import me.advait.contender.util.MessageUtil;
import me.advait.contender.vote.VoteManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class StartVoteCommand implements CommandExecutor {

    private final VoteManager voteManager;

    public StartVoteCommand(VoteManager voteManager) {
        this.voteManager = voteManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("contender.master")) {
            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">You don't have permission to do this!</color>");
            }
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("Usage: /startvote <seconds>");
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">Invalid number: " + args[0] + "</color>");
            }
            return true;
        }

        if (seconds < 5) {
            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">Minimum duration is 5 seconds!</color>");
            }
            return true;
        }

        try {
        if (!voteManager.startVote(seconds)) {
            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">A vote is already active!</color>");
            }
            return true;
        }

        } catch (IllegalStateException failure) { sender.sendMessage(failure.getMessage()); }
        return true;
    }
}
