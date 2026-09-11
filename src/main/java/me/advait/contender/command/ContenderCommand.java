package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.spectator.SpectatorManager;
import me.advait.contender.util.MessageUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class ContenderCommand implements CommandExecutor {

    private final Contender plugin;
    private final SpectatorManager spectatorManager;

    public ContenderCommand(Contender plugin, SpectatorManager spectatorManager) {
        this.plugin = plugin;
        this.spectatorManager = spectatorManager;
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
            if (!plugin.getDuelManager().getActiveDuels().isEmpty() || plugin.getArenaManager().hasPendingWork()) {
                sender.sendMessage("Wait for active matches and arena preparation to finish before reloading.");
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
                        MessageUtil.FONT_OPEN +
                                "<color:" + MessageUtil.PRIMARY + ">Reloaded. Arena copies are preparing.</color>" +
                                MessageUtil.FONT_CLOSE));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("spectator") || args[0].equalsIgnoreCase("spec")) {
            handleSpectator(sender, args);
            return true;
        }

        sendUsage(sender);
        return true;
    }

    private void handleSpectator(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR +
                            ">Usage: /contender spectator <add|remove|list> [player]</color>" + MessageUtil.FONT_CLOSE));
            return;
        }

        String sub = args[1];

        if (sub.equalsIgnoreCase("list")) {
            var specs = spectatorManager.getDeceased();
            if (specs.isEmpty()) {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.MUTED + ">No deceaseds registered.</color>" + MessageUtil.FONT_CLOSE));
            } else {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Deceased (" + specs.size() + "):</color>" + MessageUtil.FONT_CLOSE));
                for (UUID uuid : specs) {
                    Player p = Bukkit.getPlayer(uuid);
                    String name = p != null ? p.getName() : uuid.toString();
                    sender.sendMessage(MessageUtil.parse(
                            MessageUtil.FONT_OPEN + "<color:" + MessageUtil.SECONDARY + "> - " + name + "</color>" + MessageUtil.FONT_CLOSE));
                }
            }
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR +
                            ">Usage: /contender spectator <add|remove> <player></color>" + MessageUtil.FONT_CLOSE));
            return;
        }

        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR + ">Player '" + args[2] + "' not found.</color>" + MessageUtil.FONT_CLOSE));
            return;
        }

        if (sub.equalsIgnoreCase("add")) {
            if (spectatorManager.isDeceased(target)) {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.WARNING + ">" + target.getName() + " is already an deceased.</color>" + MessageUtil.FONT_CLOSE));
                return;
            }
            spectatorManager.addDeceased(target.getUniqueId());
            target.setGameMode(org.bukkit.GameMode.SPECTATOR);
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Added " + target.getName() + " as an deceased.</color>" + MessageUtil.FONT_CLOSE));
            MessageUtil.sendActionBar(target, "<color:" + MessageUtil.SECONDARY + ">You are now an deceased.</color>");

        } else if (sub.equalsIgnoreCase("remove")) {
            if (!spectatorManager.isDeceased(target)) {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.WARNING + ">" + target.getName() + " is not an deceased.</color>" + MessageUtil.FONT_CLOSE));
                return;
            }
            spectatorManager.removeDeceased(target.getUniqueId());
            target.setGameMode(org.bukkit.GameMode.SURVIVAL);
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Removed " + target.getName() + " as an deceased.</color>" + MessageUtil.FONT_CLOSE));
            MessageUtil.sendActionBar(target, "<color:" + MessageUtil.SECONDARY + ">You are no longer an deceased.</color>");

        } else {
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR +
                            ">Unknown subcommand. Usage: /contender spectator <add|remove|list></color>" + MessageUtil.FONT_CLOSE));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse(
                MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Usage: /contender <reload|spectator></color>" + MessageUtil.FONT_CLOSE));
    }
}
