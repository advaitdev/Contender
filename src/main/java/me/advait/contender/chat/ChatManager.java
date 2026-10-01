package me.advait.contender.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.role.PlayerRole;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Set;
import java.util.UUID;

/** Who may use game chat: lobby players, players in a game, and spectators each have their own switch. */
public final class ChatManager implements Listener {
    private static final Set<String> PRIVATE_MESSAGES = Set.of("/msg", "/w", "/tell", "/whisper", "/minecraft:msg", "/minecraft:w",
            "/minecraft:tell", "/minecraft:whisper", "/me", "/minecraft:me", "/teammsg", "/tm", "/minecraft:teammsg", "/minecraft:tm");
    private final Contender plugin;

    public ChatManager(Contender plugin) { this.plugin = plugin; }

    enum Category { CONTESTANT, SPECTATOR, LOBBY }

    /** Reads only thread-safe state (the registry map lookups are tolerant of concurrent reads). */
    Category category(UUID player) {
        var claim = plugin.getRegistry().claim(player);
        if (claim != null) return claim.involvement() == me.advait.contender.activity.ActivityRegistry.Involvement.WATCHING
                ? Category.SPECTATOR : Category.CONTESTANT;
        return plugin.getRoleManager().getRole(player) == PlayerRole.CONTESTANT ? Category.LOBBY : Category.SPECTATOR;
    }

    private boolean blocked(Player player) {
        ChatSettings settings = plugin.getChatSettings();
        boolean allowed = switch (category(player.getUniqueId())) {
            case CONTESTANT -> settings.isAllowContestantGameChat();
            case SPECTATOR -> settings.isAllowSpectatorGameChat();
            case LOBBY -> settings.isAllowLobbyGameChat();
        };
        return !allowed && !(settings.isAdminsOverrideAll() && player.hasPermission("contender.admin"));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!blocked(event.getPlayer())) return;
        event.setCancelled(true);
        Msg.error(event.getPlayer(), "You can't chat right now.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!PRIVATE_MESSAGES.contains(label) || !blocked(event.getPlayer())) return;
        event.setCancelled(true);
        Msg.error(event.getPlayer(), "You can't chat right now.");
    }
}
