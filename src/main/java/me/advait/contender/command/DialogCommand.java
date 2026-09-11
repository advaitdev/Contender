package me.advait.contender.command;

import me.advait.contender.dialog.Dialogs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.function.Consumer;

public final class DialogCommand implements CommandExecutor {
    private final boolean admin;
    private final Consumer<Player> open;
    public DialogCommand(boolean admin, Consumer<Player> open) { this.admin = admin; this.open = open; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Open this menu in game."); return true; }
        if (admin && !player.hasPermission("contender.master")) { Dialogs.error(player, "You don't have permission to open this menu."); return true; }
        try { open.accept(player); }
        catch (Exception failure) { Dialogs.error(player, Dialogs.message(failure)); }
        return true;
    }
}
