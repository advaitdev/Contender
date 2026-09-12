package me.advait.contender.spectator;

import me.advait.contender.Contender;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

import java.util.*;

/** Client-only allays, including a self-view. Each duel restores its own avatars and hide entries. */
public final class SpectatorVisibility {
    public static final int HIDE_DISTANCE_BLOCKS = 10;
    private final Contender plugin;
    private final Map<UUID, Watching> watching = new HashMap<>();
    private static final class Watching {
        final Player player;
        final AllayAvatar avatar;
        final boolean collision, pickup, spawning, silent, invisible, flight, flying;
        final Set<Player> hidden = new HashSet<>(), visible = new HashSet<>();
        org.bukkit.World world;
        Watching(Player player, AllayAvatar avatar) {
            this.player = player; this.avatar = avatar; world = player.getWorld();
            collision = player.isCollidable(); pickup = player.getCanPickupItems(); spawning = player.getAffectsSpawning();
            silent = player.isSilent(); invisible = player.isInvisible(); flight = player.getAllowFlight(); flying = player.isFlying();
        }
        void apply() {
            player.setCollidable(false); player.setCanPickupItems(false); player.setAffectsSpawning(false);
            player.setSilent(true); player.setInvisible(true); player.setAllowFlight(true); player.setFlying(true);
        }
        void restore() {
            player.setCollidable(collision); player.setCanPickupItems(pickup); player.setAffectsSpawning(spawning);
            player.setSilent(silent); player.setInvisible(invisible); player.setFlying(false);
            player.setAllowFlight(flight); if (flight && flying) player.setFlying(true);
        }
    }
    public SpectatorVisibility(Contender plugin) { this.plugin = plugin; }
    public void enter(Player player) {
        Watching state = watching.computeIfAbsent(player.getUniqueId(), ignored -> new Watching(player, plugin.createSpectatorAvatar(player)));
        state.apply();
    }
    public static boolean canSeeAvatar(Location owner, Location viewer, boolean viewerSpectating, boolean alwaysInvisible) {
        return owner.getWorld().equals(viewer.getWorld()) && (viewerSpectating || !alwaysInvisible
                && owner.distanceSquared(viewer) >= HIDE_DISTANCE_BLOCKS * HIDE_DISTANCE_BLOCKS);
    }
    public void update(Collection<Player> contestants, Collection<Player> spectators, boolean alwaysInvisible) {
        Set<UUID> present = new HashSet<>();
        for (Player player : spectators) { present.add(player.getUniqueId()); enter(player); }
        for (Watching state : List.copyOf(watching.values())) {
            if (!present.contains(state.player.getUniqueId()) || !state.player.isOnline()) { remove(state.player); continue; }
            Player owner = state.player;
            if (!owner.getWorld().equals(state.world)) {
                for (Player viewer : state.visible) if (viewer.isOnline()) state.avatar.destroy(viewer);
                state.visible.clear(); state.world = owner.getWorld();
            }
            state.hidden.removeIf(viewer -> !viewer.isOnline());
            state.visible.removeIf(viewer -> !viewer.isOnline());
            Component name = plugin.getNameTagManager().displayName(owner);
            Location avatarLocation = owner.getLocation().clone().add(0, 2, 0);
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                boolean self = viewer.equals(owner);
                if (!self && state.hidden.add(viewer)) viewer.hidePlayer(plugin, owner);
                var duel = plugin.getDuelManager().getDuel(viewer);
                boolean viewerSpectating = self || spectators.contains(viewer)
                        || !contestants.contains(viewer) && (duel == null || duel.isSpectator(viewer.getUniqueId()))
                        && plugin.getRoleManager().getRole(viewer.getUniqueId()) != me.advait.contender.role.PlayerRole.CONTESTANT;
                boolean show = canSeeAvatar(owner.getLocation(), viewer.getLocation(), viewerSpectating, alwaysInvisible);
                if (show) {
                    if (state.visible.add(viewer)) state.avatar.spawn(viewer, avatarLocation, name);
                    else state.avatar.move(viewer, avatarLocation, name);
                } else if (state.visible.remove(viewer)) state.avatar.destroy(viewer);
            }
        }
    }
    public void remove(Player player) {
        Watching state = watching.remove(player.getUniqueId());
        if (state == null) return;
        try {
            for (Player viewer : state.visible) if (viewer.isOnline()) state.avatar.destroy(viewer);
        } finally {
            state.restore();
            for (Player viewer : state.hidden) if (viewer.isOnline()) viewer.showPlayer(plugin, state.player);
        }
    }
    public void clear() { for (Watching state : List.copyOf(watching.values())) remove(state.player); }
}
