package me.advait.contender.command;

import me.advait.contender.SpectatorManager;
import me.advait.contender.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class SpectatorCommand implements CommandExecutor, TabCompleter {

    private final SpectatorManager spectatorManager;

    public SpectatorCommand(SpectatorManager spectatorManager) {
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

        String sub = args[0];

        if (sub.equalsIgnoreCase("list")) {
            var specs = spectatorManager.getEventSpectators();
            if (specs.isEmpty()) {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.MUTED + ">No event spectators registered.</color>" + MessageUtil.FONT_CLOSE));
            } else {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Event spectators (" + specs.size() + "):</color>" + MessageUtil.FONT_CLOSE));
                for (UUID uuid : specs) {
                    Player p = Bukkit.getPlayer(uuid);
                    String name = p != null ? p.getName() : uuid.toString();
                    sender.sendMessage(MessageUtil.parse(
                            MessageUtil.FONT_OPEN + "<color:" + MessageUtil.SECONDARY + "> - " + name + "</color>" + MessageUtil.FONT_CLOSE));
                }
            }
            return true;
        }

        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR + ">Player '" + args[1] + "' not found.</color>" + MessageUtil.FONT_CLOSE));
            return true;
        }

        if (sub.equalsIgnoreCase("add")) {
            if (spectatorManager.isEventSpectator(target)) {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.WARNING + ">" + target.getName() + " is already an event spectator.</color>" + MessageUtil.FONT_CLOSE));
                return true;
            }
            spectatorManager.addEventSpectator(target.getUniqueId());
            target.setGameMode(org.bukkit.GameMode.SPECTATOR);
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Added " + target.getName() + " as an event spectator.</color>" + MessageUtil.FONT_CLOSE));
            MessageUtil.sendActionBar(target, "<color:" + MessageUtil.SECONDARY + ">You are now an event spectator.</color>");

        } else if (sub.equalsIgnoreCase("remove")) {
            if (!spectatorManager.isEventSpectator(target)) {
                sender.sendMessage(MessageUtil.parse(
                        MessageUtil.FONT_OPEN + "<color:" + MessageUtil.WARNING + ">" + target.getName() + " is not an event spectator.</color>" + MessageUtil.FONT_CLOSE));
                return true;
            }
            spectatorManager.removeEventSpectator(target.getUniqueId());
            target.setGameMode(org.bukkit.GameMode.SURVIVAL);
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Removed " + target.getName() + " as an event spectator.</color>" + MessageUtil.FONT_CLOSE));
            MessageUtil.sendActionBar(target, "<color:" + MessageUtil.SECONDARY + ">You are no longer an event spectator.</color>");

        } else {
            sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("contender.master")) return List.of();

        if (args.length == 1) {
            return filterStartsWith(Arrays.asList("add", "remove", "list"), args[0]);
        }

        if (args.length == 2) {
            String sub = args[0];
            if (sub.equalsIgnoreCase("add") || sub.equalsIgnoreCase("remove")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    private List<String> filterStartsWith(List<String> list, String prefix) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse(
                MessageUtil.FONT_OPEN + "<color:" + MessageUtil.PRIMARY + ">Usage: /spectator <add|remove|list> [player]</color>" + MessageUtil.FONT_CLOSE));
    }
}
