package me.advait.contender.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.empty()
                        .append(StringUtil.getPlayerHead(source))
                        .append(Component.space())
                        .append(sourceDisplayName) // respects nick/plugins
                        .append(Component.text(": "))
                        .append(message)
        );
    }
}