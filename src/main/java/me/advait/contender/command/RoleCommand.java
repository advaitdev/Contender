package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.dialog.RoleDialogs;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.*;

public final class RoleCommand implements CommandExecutor, TabCompleter {
    private final Contender plugin;
    public RoleCommand(Contender plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("contender.master")) { sender.sendMessage(Component.text("You don't have permission to change roles.", NamedTextColor.RED)); return true; }
        try {
            if (args.length == 0 && sender instanceof Player player) { new RoleDialogs(plugin).open(player); return true; }
            if (args.length < 1 || args.length > 2) { sender.sendMessage("Usage: /role <player> [director|spectator|contestant]"); return true; }
            OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) throw new IllegalArgumentException("That player hasn't joined this server. Use their current Minecraft name.");
            if (args.length == 1) {
                if (sender instanceof Player player) new RoleDialogs(plugin).edit(player, target.getUniqueId(), target.getName());
                else sender.sendMessage(target.getName() + ": " + plugin.getRoleManager().getRole(target.getUniqueId()).label());
            } else {
                PlayerRole role = PlayerRole.parse(args[1]);
                plugin.getRoleManager().setRole(target.getUniqueId(), role);
                sender.sendMessage(Component.text(target.getName() + " is now a " + role.id() + ".", NamedTextColor.GREEN));
            }
        } catch (IllegalArgumentException | IllegalStateException failure) { sender.sendMessage(Component.text(failure.getMessage(), NamedTextColor.RED)); }
        return true;
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("contender.master")) return List.of();
        var options = args.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 2 ? Arrays.stream(PlayerRole.values()).map(PlayerRole::id).toList() : List.<String>of();
        String start = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }
}
