package me.advait.contender.spectate;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;

/**
 * Client-side allays that show where spectators are. They exist only as packets, so they have no
 * hitbox and cannot affect a match. Competitors see them only from a distance.
 */
final class SpectatorAvatars {
    static final int HIDE_DISTANCE = 10;
    private static final double VIEW_DISTANCE = 96;
    private final Contender plugin;
    private final SpectateService service;
    private final PaperAllayAvatar.Transport transport;
    private final Map<UUID, Shown> shown = new HashMap<>();

    private static final class Shown {
        final AllayAvatar avatar;
        final Set<UUID> viewers = new HashSet<>();
        Shown(AllayAvatar avatar) { this.avatar = avatar; }
    }

    SpectatorAvatars(Contender plugin, SpectateService service) {
        this.plugin = plugin;
        this.service = service;
        PaperAllayAvatar.Transport created = null;
        if (plugin.getConfig().getBoolean("spectators.allay-avatars", true)) {
            try { created = new PaperAllayAvatar.Transport(); }
            catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING, "Spectator allays are unavailable on this server version; spectators stay invisible.", failure);
            }
        }
        transport = created;
    }

    /** Spectators to draw: watchers and players knocked out of the current round. */
    private Map<UUID, Spectatable> owners() {
        Map<UUID, Spectatable> owners = new HashMap<>(service.watching());
        for (Duel duel : plugin.getDuels().duels()) {
            for (UUID id : duel.players()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.getGameMode() == GameMode.SPECTATOR) owners.put(id, duel);
            }
        }
        return owners;
    }

    void update() {
        if (transport == null) return;
        Map<UUID, Spectatable> owners = owners();
        for (UUID id : List.copyOf(shown.keySet())) if (!owners.containsKey(id)) remove(id);
        for (var entry : owners.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null || owner.getGameMode() != GameMode.SPECTATOR) { remove(entry.getKey()); continue; }
            Spectatable target = entry.getValue();
            Shown state = shown.computeIfAbsent(owner.getUniqueId(), ignored -> new Shown(new PaperAllayAvatar(transport, owner)));
            Location at = owner.getLocation().add(0, 0.4, 0);
            Component name = plugin.getNameTagManager().displayName(owner);
            Set<UUID> wanted = new HashSet<>();
            for (Player viewer : owner.getWorld().getPlayers()) {
                if (viewer.equals(owner) || viewer.getLocation().distanceSquared(at) > VIEW_DISTANCE * VIEW_DISTANCE) continue;
                boolean competing = target.players().contains(viewer.getUniqueId()) && viewer.getGameMode() != GameMode.SPECTATOR;
                if (competing && (target.hidesAvatars() || viewer.getLocation().distanceSquared(at) < HIDE_DISTANCE * HIDE_DISTANCE)) continue;
                wanted.add(viewer.getUniqueId());
            }
            try {
                for (UUID viewerId : List.copyOf(state.viewers)) {
                    if (wanted.contains(viewerId)) continue;
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null) state.avatar.destroy(viewer);
                    state.viewers.remove(viewerId);
                }
                for (UUID viewerId : wanted) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer == null) continue;
                    if (state.viewers.add(viewerId)) state.avatar.spawn(viewer, at, name);
                    else state.avatar.move(viewer, at, name);
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Could not update a spectator allay; hiding it.", failure);
                remove(owner.getUniqueId());
            }
        }
    }

    void remove(UUID owner) {
        Shown state = shown.remove(owner);
        if (state == null) return;
        for (UUID viewerId : state.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) continue;
            try { state.avatar.destroy(viewer); } catch (RuntimeException ignored) { }
        }
    }

    void clear() { for (UUID id : List.copyOf(shown.keySet())) remove(id); }
}
