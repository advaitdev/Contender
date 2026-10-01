package me.advait.contender.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.Contender;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Chat lines start with the speaker's head and tag. Only admins may use formatting tags. */
public final class ChatListener implements Listener {
    private static final MiniMessage SAFE = MiniMessage.builder()
            .tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow()))
            .build();
    private final Contender plugin;

    public ChatListener(Contender plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        boolean formatting = event.getPlayer().hasPermission("contender.admin");
        String raw = event.signedMessage().message();
        Component message = formatting ? SAFE.deserialize(raw) : Component.text(raw);
        event.renderer((source, sourceDisplayName, ignored, viewer) -> Component.empty()
                .append(StringUtil.getPlayerHead(source))
                .append(Component.space())
                .append(plugin.getNameTagManager().displayName(source))
                .append(Component.text(": "))
                .append(message));
    }
}
