package me.advait.contender.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.role.RoleManager;
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
    private final RoleManager roleManager;
    private final java.util.function.Supplier<me.advait.contender.voice.VoiceRouting> routing;

    public ChatManager(ChatSettings chatSettings, DuelManager duelManager, RoleManager roleManager) {
        this(chatSettings, duelManager, roleManager, () -> null);
    }
    public ChatManager(ChatSettings chatSettings, DuelManager duelManager, RoleManager roleManager,
                       java.util.function.Supplier<me.advait.contender.voice.VoiceRouting> routing) {
        this.routing = routing;
        this.chatSettings = chatSettings;
        this.duelManager = duelManager;
        this.roleManager = roleManager;
    }

    enum PlayerCategory { CONTESTANT, SPECTATOR, LOBBY }

    private PlayerCategory getCategory(UUID uuid) {
        // Async chat reads the immutable voice snapshot, never live minigame state.
        var voice = routing.get();
        var member = voice == null ? null : voice.snapshot().get(uuid);
        if (member != null && member.event() != null) return member.spectator() ? PlayerCategory.SPECTATOR : PlayerCategory.CONTESTANT;
        Duel duel = duelManager.getDuel(uuid);
        if (duel != null) {
            return duel.isSpectator(uuid) ? PlayerCategory.SPECTATOR : PlayerCategory.CONTESTANT;
        }
        if (!roleManager.isContestant(uuid)) return PlayerCategory.SPECTATOR;
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
            player.sendMessage(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                            "<color:#FF4444>You cannot chat right now.</color>"
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
            player.sendMessage(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                            "<color:#FF4444>You cannot chat right now.</color>"
                    )
            );
        }
    }
}
