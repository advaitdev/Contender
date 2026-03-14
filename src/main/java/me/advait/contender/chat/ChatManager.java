package me.advait.contender.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.spectator.SpectatorManager;
import me.advait.contender.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Set;
import java.util.UUID;

public class ChatManager implements Listener {

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "/msg", "/w", "/tell", "/whisper",
            "/minecraft:msg", "/minecraft:tell", "/minecraft:whisper",
            "/me", "/minecraft:me"
    );

    private final ChatSettings chatSettings;
    private final DuelManager duelManager;
    private final SpectatorManager spectatorManager;

    public ChatManager(ChatSettings chatSettings, DuelManager duelManager, SpectatorManager spectatorManager) {
        this.chatSettings = chatSettings;
        this.duelManager = duelManager;
        this.spectatorManager = spectatorManager;
    }

    enum PlayerCategory { CONTESTANT, SPECTATOR, LOBBY }

    private PlayerCategory getCategory(UUID uuid) {
        Duel duel = duelManager.getDuel(uuid);
        if (duel != null) {
            return duel.isSpectator(uuid) ? PlayerCategory.SPECTATOR : PlayerCategory.CONTESTANT;
        }
        if (spectatorManager.isDeceased(uuid)) return PlayerCategory.SPECTATOR;
        return PlayerCategory.LOBBY;
    }

    private boolean shouldBlockChat(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerCategory cat = getCategory(uuid);

        boolean allowed = switch (cat) {
            case CONTESTANT -> chatSettings.isAllowContestantGameChat();
            case SPECTATOR -> chatSettings.isAllowSpectatorGameChat();
            case LOBBY -> chatSettings.isAllowLobbyGameChat();
        };

        if (allowed) return false;

        // If admins override all and player has admin permission, don't block
        if (chatSettings.isAdminsOverrideAll() && player.hasPermission("contender.admin")) return false;

        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (shouldBlockChat(player)) {
            event.setCancelled(true);
            player.sendActionBar(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                            MessageUtil.FONT_OPEN + "<color:#FF4444>You cannot chat right now.</color>" + MessageUtil.FONT_CLOSE
                    )
            );
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String commandLabel = message.split(" ")[0].toLowerCase();

        if (!BLOCKED_COMMANDS.contains(commandLabel)) return;

        if (shouldBlockChat(player)) {
            event.setCancelled(true);
            player.sendActionBar(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                            MessageUtil.FONT_OPEN + "<color:#FF4444>You cannot chat right now.</color>" + MessageUtil.FONT_CLOSE
                    )
            );
        }
    }
}
