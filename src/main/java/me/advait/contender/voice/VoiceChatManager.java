package me.advait.contender.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import me.advait.contender.chat.ChatSettings;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.util.MessageUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceChatManager implements VoicechatPlugin {

    private final Plugin plugin;
    private final ChatSettings chatSettings;
    private final DuelManager duelManager;

    // Thread-safe sets: updated on main thread, read on SVC thread
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deafenedPlayers = ConcurrentHashMap.newKeySet();

    private VoiceChatManager(Plugin plugin, ChatSettings chatSettings, DuelManager duelManager) {
        this.plugin = plugin;
        this.chatSettings = chatSettings;
        this.duelManager = duelManager;
    }

    // Called from Contender.onEnable() — registers via BukkitVoicechatService (the correct Bukkit API)
    public static void setup(Plugin plugin, ChatSettings chatSettings, DuelManager duelManager) {
        RegisteredServiceProvider<BukkitVoicechatService> provider =
                Bukkit.getServicesManager().getRegistration(BukkitVoicechatService.class);
        if (provider == null) {
            plugin.getLogger().warning("Simple Voice Chat not found — voice chat features disabled.");
            return;
        }
        provider.getProvider().registerPlugin(
                new VoiceChatManager(plugin, chatSettings, duelManager));
    }

    @Override
    public String getPluginId() { return "contender"; }

    @Override
    public void initialize(VoicechatApi api) {
        // SVC calls this after registerPlugin() — start the main-thread tick now
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onEntitySound);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onLocationalSound);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onStaticSound);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        if (event.getSenderConnection() == null) return;
        if (!(event.getSenderConnection().getPlayer().getPlayer() instanceof Player player)) return;
        if (mutedPlayers.contains(player.getUniqueId())) {
            event.cancel();
        }
    }

    private void onEntitySound(EntitySoundPacketEvent event) {
        if (event.getReceiverConnection() == null) return;
        if (deafenedPlayers.contains(event.getReceiverConnection().getPlayer().getUuid())) {
            event.cancel();
        }
    }

    private void onLocationalSound(LocationalSoundPacketEvent event) {
        if (event.getReceiverConnection() == null) return;
        if (deafenedPlayers.contains(event.getReceiverConnection().getPlayer().getUuid())) {
            event.cancel();
        }
    }

    private void onStaticSound(StaticSoundPacketEvent event) {
        if (event.getReceiverConnection() == null) return;
        if (deafenedPlayers.contains(event.getReceiverConnection().getPlayer().getUuid())) {
            event.cancel();
        }
    }

    private void tick() {
        Set<UUID> newMuted = new HashSet<>();
        Set<UUID> newDeafened = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (chatSettings.isAdminsOverrideAll() && player.hasPermission("contender.admin")) continue;

            PlayerCategory cat = getCategory(uuid);
            if (shouldMute(cat)) newMuted.add(uuid);
            if (shouldDeafen(cat)) newDeafened.add(uuid);
        }

        // Notify players whose mute state changed
        for (UUID uuid : newMuted) {
            if (!mutedPlayers.contains(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) sendMessage(p, "<color:" + MessageUtil.WARNING + ">Your voice chat has been muted by an admin.</color>");
            }
        }
        for (UUID uuid : mutedPlayers) {
            if (!newMuted.contains(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) sendMessage(p, "<color:" + MessageUtil.PRIMARY + ">Your voice chat mute has been lifted.</color>");
            }
        }

        // Notify players whose deafen state changed
        for (UUID uuid : newDeafened) {
            if (!deafenedPlayers.contains(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) sendMessage(p, "<color:" + MessageUtil.WARNING + ">Your voice chat has been deafened by an admin.</color>");
            }
        }
        for (UUID uuid : deafenedPlayers) {
            if (!newDeafened.contains(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) sendMessage(p, "<color:" + MessageUtil.PRIMARY + ">Your voice chat deafen has been lifted.</color>");
            }
        }

        mutedPlayers.removeIf(uuid -> !newMuted.contains(uuid));
        mutedPlayers.addAll(newMuted);
        deafenedPlayers.removeIf(uuid -> !newDeafened.contains(uuid));
        deafenedPlayers.addAll(newDeafened);
    }

    private boolean shouldMute(PlayerCategory cat) {
        return switch (cat) {
            case CONTESTANT -> chatSettings.isMuteContestantVoiceChat();
            case SPECTATOR -> chatSettings.isMuteSpectatorVoiceChat();
            case LOBBY -> chatSettings.isMuteLobbyVoiceChat();
        };
    }

    private boolean shouldDeafen(PlayerCategory cat) {
        return switch (cat) {
            case CONTESTANT -> chatSettings.isDeafenContestantVoiceChat();
            case SPECTATOR -> chatSettings.isDeafenSpectatorVoiceChat();
            case LOBBY -> false;
        };
    }

    private void sendMessage(Player player, String miniMessage) {
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                MessageUtil.FONT_OPEN + miniMessage + MessageUtil.FONT_CLOSE));
    }

    enum PlayerCategory { CONTESTANT, SPECTATOR, LOBBY }

    private PlayerCategory getCategory(UUID uuid) {
        Duel duel = duelManager.getDuel(uuid);
        if (duel != null) {
            return duel.isSpectator(uuid) ? PlayerCategory.SPECTATOR : PlayerCategory.CONTESTANT;
        }
        return PlayerCategory.LOBBY;
    }
}
