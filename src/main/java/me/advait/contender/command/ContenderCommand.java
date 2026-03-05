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
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Usage: /contender reload</color>" +
                            MessageUtil.FONT_CLOSE));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getDuelManager().cleanup();
            plugin.getMapManager().loadMaps();
            plugin.getKitManager().loadKits();

            if (sender instanceof Player player) {
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.PRIMARY + ">Contender reloaded! All active duels cancelled.</color>");
                MessageUtil.playSuccess(player);
            } else {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN +
                                "<color:" + MessageUtil.PRIMARY + ">Contender reloaded! All active duels cancelled.</color>" +
                                MessageUtil.FONT_CLOSE));
            }
            return true;
        }

        sender.sendMessage(MessageUtil.parse(
                MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR + ">Unknown subcommand. Usage: /contender reload</color>" +
                        MessageUtil.FONT_CLOSE));
        return true;
    }
}
