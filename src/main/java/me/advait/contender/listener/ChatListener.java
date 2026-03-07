package me.advait.contender.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component parsedMessage = MiniMessage.miniMessage().deserialize(event.signedMessage().message());

            return Component.empty()
                    .append(StringUtil.getPlayerHead(source))
                    .append(Component.space())
                    .append(sourceDisplayName)
                    .append(Component.text(": "))
                    .append(parsedMessage);
        });
    }

}