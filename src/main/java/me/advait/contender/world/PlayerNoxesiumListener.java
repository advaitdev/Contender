package me.advait.contender.world;

import com.noxcrew.noxesium.api.player.NoxesiumPlayerManager;
import me.advait.contender.Contender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerNoxesiumListener implements Listener {


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().scheduleSyncDelayedTask(JavaPlugin.getPlugin(Contender.class), () -> {
            if (NoxesiumPlayerManager.getInstance().getPlayer(event.getPlayer().getUniqueId()) != null) {
                event.getPlayer().sendMessage(Component.text("You have been handshook successfully with Noxesium.").color(NamedTextColor.GREEN));
            } else {
                event.getPlayer().kick(Component.text("You must install Noxesium to connect to this server!").color(NamedTextColor.RED));
            }
        }, 60);
    }


}
