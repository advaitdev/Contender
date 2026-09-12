package me.advait.contender.duel;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Temporary round-reset physics with originals retained through respawn and saved with player data. */
public final class RoundResetProtection implements AutoCloseable {
    private static final NamespacedKey GRAVITY = new NamespacedKey("contender", "round_reset_gravity");
    private record Saved(Player player, boolean gravity) { }
    private final Map<UUID, Saved> frozen = new HashMap<>();

    public void freeze(Player player) {
        var data = player.getPersistentDataContainer();
        Saved saved = frozen.get(player.getUniqueId());
        if (saved == null) {
            Byte original = data.get(GRAVITY, PersistentDataType.BYTE);
            saved = new Saved(player, original == null ? player.hasGravity() : original != 0);
        } else if (saved.player() != player) {
            saved = new Saved(player, saved.gravity());
        }
        frozen.put(player.getUniqueId(), saved);
        data.set(GRAVITY, PersistentDataType.BYTE, (byte) (saved.gravity() ? 1 : 0));
        player.setGravity(false);
        player.setVelocity(new Vector());
        player.setFallDistance(0);
    }

    public void release(Player player) {
        Saved saved = frozen.get(player.getUniqueId());
        if (saved == null) return;
        restore(player, saved.gravity());
        frozen.remove(player.getUniqueId());
    }

    @Override public void close() {
        RuntimeException incomplete = null;
        for (Saved saved : List.copyOf(frozen.values())) {
            try { release(saved.player()); }
            catch (RuntimeException | Error failure) {
                if (incomplete == null) incomplete = new IllegalStateException("Could not restore every player's gravity after the round reset.");
                incomplete.addSuppressed(failure);
            }
        }
        if (incomplete != null) throw incomplete;
    }

    /** Recover a temporary flag saved by a server crash, before joining the lobby or another game. */
    public static void recover(Player player) {
        Byte original = player.getPersistentDataContainer().get(GRAVITY, PersistentDataType.BYTE);
        if (original != null) restore(player, original != 0);
    }

    private static void restore(Player player, boolean gravity) {
        player.setGravity(gravity);
        player.getPersistentDataContainer().remove(GRAVITY);
    }

    public static final class RecoveryListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onJoin(PlayerJoinEvent event) { recover(event.getPlayer()); }
    }
}
