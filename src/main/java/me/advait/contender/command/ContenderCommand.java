package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;


public class ContenderCommand implements CommandExecutor {

    private final Contender plugin;

    public ContenderCommand(Contender plugin) {
        this.plugin = plugin;
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
            sendUsage(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!plugin.getDuelManager().getActiveDuels().isEmpty() || plugin.getArenaManager().hasPendingWork()
                    || plugin.getMinigameManager() != null && plugin.getMinigameManager().busy()) {
                sender.sendMessage("Wait for active games and arena preparation to finish before reloading.");
                return true;
            }
            plugin.getTournamentManager().pause();
            plugin.reloadConfig();
            plugin.getNameTagManager().refresh();
            plugin.getMapManager().loadMaps();
            plugin.getKitManager().loadKits();
            plugin.getArenaManager().reloadTemplates();

            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.PRIMARY + ">Reloaded. Arena copies are preparing.</color>");
                MessageUtil.playSuccess(player);
            } else {
                sender.sendMessage(MessageUtil.parse(
                        "<color:" + MessageUtil.PRIMARY + ">Reloaded. Arena copies are preparing.</color>"));
            }
            return true;
        }

        sendUsage(sender);
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse(
                "<color:" + MessageUtil.PRIMARY + ">Usage: /contender reload</color>"));
    }
}
