package me.advait.contender.command;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import me.advait.contender.Contender;
import me.advait.contender.ContenderBootstrap;
import me.advait.contender.core.Msg;
import me.advait.contender.dialog.HackerDialogs;
import me.advait.contender.dialog.SpectateDialogs;
import me.advait.contender.dialog.VoteDialogs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.function.Consumer;

/** Handles the buttons in the Quick Actions menu registered by the bootstrapper. */
public final class QuickActions implements Listener {
    private final Contender plugin;

    public QuickActions(Contender plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        var key = event.getIdentifier();
        Consumer<Player> action;
        if (key.equals(ContenderBootstrap.OPEN_SPECTATE)) action = p -> new SpectateDialogs(plugin).open(p);
        else if (key.equals(ContenderBootstrap.OPEN_VOTE)) action = p -> new VoteDialogs(plugin).open(p);
        else if (key.equals(ContenderBootstrap.OPEN_LOBBY)) action = p -> Commands.lobby(plugin, p);
        else if (key.equals(ContenderBootstrap.OPEN_HACKS)) action = p -> new HackerDialogs(plugin).open(p);
        else return;
        Player player = connection.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            try { action.accept(player); }
            catch (RuntimeException failure) { Msg.error(player, Msg.reason(failure)); }
        });
    }
}
