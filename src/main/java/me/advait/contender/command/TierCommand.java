package me.advait.contender.command;

import me.advait.contender.Contender;
import me.advait.contender.tier.TierFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TierCommand implements CommandExecutor {
    private final Contender plugin;
    public TierCommand(Contender plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String name = args.length == 1 ? args[0] : args.length == 0 && sender instanceof Player player ? player.getName() : null;
        if (name == null) { sender.sendMessage("Usage: /tier <player>"); return true; }
        sender.sendMessage(Component.text("Looking up " + name + " on MCTiers...", NamedTextColor.GRAY));
        plugin.getTierService().byName(name).whenComplete((data, failure) -> {
            if (!plugin.isEnabled() || sender instanceof Player player && !player.isOnline()) return;
            if (failure != null) { sender.sendMessage(Component.text("Couldn't load tiers. Try again in a minute.", NamedTextColor.RED)); return; }
            if (data == null) { sender.sendMessage(Component.text("No MCTiers profile found for " + name + ".", NamedTextColor.GRAY)); return; }
            sender.sendMessage(Component.text(data.name() + " · MCTiers", NamedTextColor.WHITE));
            var ranks = TierFormatter.rankings(data);
            if (ranks.isEmpty()) sender.sendMessage(Component.text("No rankings found.", NamedTextColor.GRAY));
            for (var rank : ranks) sender.sendMessage(TierFormatter.format(rank).append(Component.text("  " + rank.mode(), NamedTextColor.GRAY)));
            plugin.getNameTagManager().refresh();
        });
        return true;
    }
}
