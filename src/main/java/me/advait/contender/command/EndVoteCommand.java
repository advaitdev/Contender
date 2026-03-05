package me.advait.contender.command;

import me.advait.contender.util.MessageUtil;
import me.advait.contender.vote.VoteManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class EndVoteCommand implements CommandExecutor {

    private final VoteManager voteManager;

    public EndVoteCommand(VoteManager voteManager) {
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

        if (!voteManager.isVoteActive()) {
            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">There is no active vote!</color>");
            } else {
                sender.sendMessage("There is no active vote.");
            }
            return true;
        }

        voteManager.endVote();

        if (sender instanceof Player player) {
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.PRIMARY + ">Vote ended!</color>");
        }
        return true;
    }
}
