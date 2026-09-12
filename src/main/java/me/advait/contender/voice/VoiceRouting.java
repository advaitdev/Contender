package me.advait.contender.voice;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import me.advait.contender.game.AbstractGameState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Publishes immutable routing decisions to the voice thread. No Bukkit calls on that thread. */
public final class VoiceRouting extends AbstractGameState {
    public record Member(Duel match, boolean spectator, boolean muted, boolean deafened, boolean hearMatch, UUID channel, UUID event) {
        public Member(Duel match, boolean spectator, boolean muted, boolean deafened, boolean hearMatch, UUID channel) {
            this(match, spectator, muted, deafened, hearMatch, channel, null);
        }
    }
    private final Contender contender;
    private final VoiceDiagnostics diagnostics;
    private volatile boolean active;
    private volatile Map<UUID, Member> members = Map.of();
    public VoiceRouting(Contender plugin) {
        super(plugin);
        contender = plugin;
        diagnostics = plugin.getVoiceDiagnostics() == null ? new VoiceDiagnostics() : plugin.getVoiceDiagnostics();
    }
    @Override protected void onEnable() { refresh(); active = true; runRepeating(this::refresh, 1, 1); }
    @Override protected void onDisable() {
        // Simple Voice Chat retains registered callbacks; they must become inert before clearing members.
        active = false;
        members = Map.of();
        diagnostics.update(members);
        diagnostics.integration("Disabled");
    }
    public boolean isActive() { return active; }
    VoiceDiagnostics diagnostics() { return diagnostics; }
    public Map<UUID, Member> snapshot() { return members; }

    public void refresh() {
        if (!isEnabled()) return;
        var settings = contender.getChatSettings();
        Map<UUID, Member> next = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Duel duel = contender.getDuelManager().getDuel(id);
            var minigames = contender.getMinigameManager();
            UUID event = minigames == null ? null : minigames.activityId(id);
            boolean spectator = duel != null ? duel.isSpectator(id) : !contender.getRoleManager().isContestant(id)
                    || (minigames != null ? minigames.isSpectator(id)
                    : contender.getRaceManager() != null && contender.getRaceManager().owns(id) && contender.getRaceManager().current().racer(id).done());
            boolean bypass = settings.isAdminsOverrideAll() && player.hasPermission("contender.admin");
            boolean muted = !bypass && (spectator ? settings.isMuteSpectatorVoiceChat()
                    : duel != null || event != null ? settings.isMuteContestantVoiceChat() : settings.isMuteLobbyVoiceChat());
            boolean deafened = !bypass && (spectator ? settings.isDeafenSpectatorVoiceChat()
                    : (duel != null || event != null) && settings.isDeafenContestantVoiceChat());
            Member old = members.get(id);
            UUID channel = old == null ? channelId(id) : old.channel();
            next.put(id, new Member(duel, spectator, muted, deafened, settings.isSpectatorsHearMatch(), channel, event));
        }
        members = Map.copyOf(next);
        diagnostics.update(members);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { refresh(); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Map<UUID, Member> next = new HashMap<>(members);
        next.remove(event.getPlayer().getUniqueId()); members = Map.copyOf(next);
        diagnostics.update(members);
    }
    static UUID channelId(UUID id) {
        return UUID.nameUUIDFromBytes(("contender:voice:" + id).getBytes(StandardCharsets.UTF_8));
    }
    public static boolean hearsSpectatorChannel(Member speaker, Member listener) {
        if (speaker == null || listener == null || speaker.muted() || listener.deafened() || !listener.spectator()) return false;
        return speaker.spectator() || listener.hearMatch() && (speaker.match() != null && speaker.match() == listener.match()
                || speaker.event() != null && speaker.event().equals(listener.event()));
    }
}
