package me.advait.contender.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Packet counts only; microphone contents are never retained. */
public final class VoiceDiagnostics {
    private static final class Traffic {
        final AtomicLong microphone = new AtomicLong(), incomingBlocked = new AtomicLong();
        final AtomicLong outgoingAllowed = new AtomicLong(), outgoingBlocked = new AtomicLong();
        final AtomicLong upstreamIncoming = new AtomicLong(), upstreamOutgoing = new AtomicLong();
        volatile long lastMicrophone;
        volatile String lastBlock;
    }

    private volatile Map<UUID, VoiceRouting.Member> members = Map.of();
    private final Map<UUID, Traffic> traffic = new ConcurrentHashMap<>();
    private volatile String integration = "Not registered";
    private volatile Function<UUID, String> connection = id -> "Not available";

    void update(Map<UUID, VoiceRouting.Member> members) {
        this.members = members;
        traffic.keySet().retainAll(members.keySet());
    }

    void integration(String status) { integration = status; }
    void connection(Function<UUID, String> probe) { connection = probe; }

    private Traffic traffic(UUID id) {
        return members.containsKey(id) ? traffic.computeIfAbsent(id, ignored -> new Traffic()) : null;
    }

    void microphone(UUID id, boolean upstreamCancelled) {
        Traffic stats = traffic(id);
        if (stats == null) return;
        stats.microphone.incrementAndGet();
        stats.lastMicrophone = System.nanoTime();
        if (upstreamCancelled) stats.upstreamIncoming.incrementAndGet();
    }

    void blockedMicrophone(UUID id, String reason) {
        Traffic stats = traffic(id);
        if (stats == null) return;
        stats.incomingBlocked.incrementAndGet();
        stats.lastBlock = reason;
    }

    void outgoing(UUID receiver, String blockReason, boolean upstreamCancelled) {
        Traffic stats = traffic(receiver);
        if (stats == null) return;
        if (upstreamCancelled) stats.upstreamOutgoing.incrementAndGet();
        else if (blockReason == null) stats.outgoingAllowed.incrementAndGet();
        else {
            stats.outgoingBlocked.incrementAndGet();
            stats.lastBlock = blockReason;
        }
    }

    public List<String> describe(UUID player) {
        List<String> lines = new ArrayList<>();
        lines.add("Contender voice hook: " + integration);
        try { lines.add("Voice connection: " + connection.apply(player)); }
        catch (RuntimeException failure) { lines.add("Voice connection: Could not read status"); }
        var member = members.get(player);
        if (member == null) {
            lines.add("Player is missing from Contender's voice routing.");
            return List.copyOf(lines);
        }
        lines.add("Voice channel: " + (member.spectator() ? "Spectators"
                : member.match() != null || member.event() != null ? "Contestants" : "Lobby"));
        lines.add("Contender speaking: " + (member.muted() ? "Muted" : "Allowed"));
        lines.add("Contender hearing: " + (member.deafened() ? "Deafened" : "Allowed"));
        if (member.spectator()) lines.add("Hear the match: " + (member.hearMatch() ? "Allowed" : "Blocked"));
        Traffic stats = traffic(player);
        if (stats == null) return List.copyOf(lines);
        lines.add("Microphone packets received: " + stats.microphone.get());
        lines.add("Last microphone packet: " + (stats.lastMicrophone == 0 ? "None"
                : Math.max(0, (System.nanoTime() - stats.lastMicrophone) / 1_000_000_000L) + "s ago"));
        lines.add("Microphone packets blocked by Contender: " + stats.incomingBlocked.get());
        lines.add("Audio packets allowed by Contender: " + stats.outgoingAllowed.get());
        lines.add("Audio packets blocked by Contender: " + stats.outgoingBlocked.get());
        if (stats.upstreamIncoming.get() != 0 || stats.upstreamOutgoing.get() != 0) {
            lines.add("Already blocked before Contender: " + stats.upstreamIncoming.get() + " microphone, "
                    + stats.upstreamOutgoing.get() + " audio");
        }
        if (stats.lastBlock != null) lines.add("Last Contender block: " + stats.lastBlock);
        lines.add("Counts cover this connection. Speak, then run this command again to check for changes.");
        lines.add("Allowed counts do not confirm client playback. Other plugins can block packets before Contender sees them.");
        return List.copyOf(lines);
    }
}
