package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.util.MessageUtil;
import net.kyori.adventure.text.Component;
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

        if (args[0].equalsIgnoreCase("voice")) {
            voiceStatus(sender, args);
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
            plugin.loadConfiguredWorlds();
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
        sender.sendMessage(DialogPalette.text("Usage: /contender reload", DialogPalette.MUTED));
        sender.sendMessage(DialogPalette.text("       /contender voice [player]", DialogPalette.MUTED));
    }

    private void voiceStatus(CommandSender sender, String[] args) {
        if (args.length > 2 || args.length == 1 && !(sender instanceof Player)) {
            sender.sendMessage(DialogPalette.text("Usage: /contender voice <player>", DialogPalette.MUTED));
            return;
        }
        Player target = args.length == 1 ? (Player) sender : plugin.getServer().getPlayerExact(args[1]);
        if (target == null || !target.isOnline()) {
            String name = args.length > 1 ? args[1] : sender.getName();
            sender.sendMessage(DialogPalette.text(name + " is not online.", DialogPalette.DANGER));
            return;
        }
        sender.sendMessage(DialogPalette.text("Voice chat for " + target.getName(), DialogPalette.ACCENT));
        for (String line : plugin.getVoiceDiagnostics().describe(target.getUniqueId())) {
            sender.sendMessage(DialogPalette.text(line, DialogPalette.TEXT));
        }
        sender.sendMessage(DialogPalette.text("Simple Voice Chat permissions:", DialogPalette.MUTED));
        sender.sendMessage(permissionStatus(target, "Speak", "voicechat.speak")
                .append(DialogPalette.text(" · ", DialogPalette.MUTED))
                .append(permissionStatus(target, "Listen", "voicechat.listen"))
                .append(DialogPalette.text(" · ", DialogPalette.MUTED))
                .append(permissionStatus(target, "Groups", "voicechat.groups")));
    }

    private Component permissionStatus(Player player, String label, String permission) {
        boolean allowed = player.hasPermission(permission);
        return DialogPalette.text(label + ": ", DialogPalette.MUTED)
                .append(DialogPalette.text(allowed ? "Allowed" : "Blocked", allowed ? DialogPalette.SUCCESS : DialogPalette.DANGER));
    }
}
