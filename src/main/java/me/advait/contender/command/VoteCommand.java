package me.advait.contender.command;

import me.advait.contender.gui.vote.VoteGUI;
import me.advait.contender.spectator.SpectatorManager;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.vote.VoteManager;
import me.advait.contender.vote.VoteSession;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VoteCommand implements CommandExecutor {

    private final VoteManager voteManager;
    private final SpectatorManager spectatorManager;

    public VoteCommand(VoteManager voteManager, SpectatorManager spectatorManager) {
        this.voteManager = voteManager;
        this.spectatorManager = spectatorManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        VoteSession session = voteManager.getActiveSession();
        if (session == null) {
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">There is no active vote!</color>");
            return true;
        }

        VoteGUI.open(player, session, spectatorManager);
        return true;
    }
}
