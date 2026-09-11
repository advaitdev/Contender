package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.dialog.SettingsDialogs;
import me.advait.contender.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SettingsCommand implements CommandExecutor {

    private final Contender plugin;
    public SettingsCommand(Contender plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.parse(
                    MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR + ">This command can only be used by players.</color>" + MessageUtil.FONT_CLOSE));
            return true;
        }
        new SettingsDialogs(plugin).open(player);
        return true;
    }
}
