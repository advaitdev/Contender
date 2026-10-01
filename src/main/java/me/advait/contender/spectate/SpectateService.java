package me.advait.contender.spectate;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.dialog.DialogPalette;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;

/**
 * Watching a match or minigame. Watchers use Minecraft's Spectator mode, so they cannot touch, block,
 * or be hit by anything. Their inventory and game mode are restored when they stop watching.
 */
public final class SpectateService extends Module implements Activity {
    private final Map<UUID, Spectatable> watching = new HashMap<>();
    private final Set<UUID> moving = new HashSet<>();
    private SpectatorAvatars avatars;

    public SpectateService(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        avatars = new SpectatorAvatars(plugin, this);
        tasks.repeat(2, 2, avatars::update);
    }

    @Override protected void onDisable() {
        for (UUID id : List.copyOf(watching.keySet())) stop(id, true);
        if (avatars != null) avatars.clear();
    }

    @Override public String displayName() { return "spectating"; }

    public Spectatable target(UUID player) { return watching.get(player); }
    public boolean isWatching(UUID player) { return watching.containsKey(player); }
    Map<UUID, Spectatable> watching() { return Collections.unmodifiableMap(watching); }

    public void watch(Player player, Spectatable target) {
        UUID id = player.getUniqueId();
        if (!target.acceptsWatchers()) throw new IllegalStateException("That match has ended.");
        if (target.players().contains(id)) throw new IllegalStateException("You are playing in that match.");
        ActivityRegistry registry = plugin.getRegistry();
        Activity owner = registry.owner(id);
        Spectatable previous = watching.get(id);
        if (owner != null && owner != this) throw new IllegalStateException("You are busy in " + owner.displayName() + ".");
        if (previous == target) return;
        if (player.isDead()) throw new IllegalStateException("Respawn first.");
        boolean fresh = previous == null;
        if (fresh) {
            registry.claim(id, this, ActivityRegistry.Involvement.WATCHING);
            plugin.getSnapshots().capture(player);
        }
        Location spawn = target.spectatorSpawn();
        Location focus = target.focus();
        if (spawn != null && focus != null && focus.getWorld() == spawn.getWorld() && focus.distanceSquared(spawn) > 1) {
            spawn = spawn.clone().setDirection(focus.toVector().subtract(spawn.toVector()));
        }
        player.setGameMode(GameMode.SPECTATOR);
        if (spawn == null || !move(player, spawn)) {
            if (fresh) {
                registry.release(id, this);
                plugin.getSnapshots().restore(player);
            }
            throw new IllegalStateException("Could not move you to that match. Try again.");
        }
        if (previous != null) previous.removeWatcher(id);
        target.addWatcher(id);
        watching.put(id, target);
        player.getInventory().setHeldItemSlot(0);
        Msg.hint(player, "Watching " + target.displayName() + ". Use /spectate to switch or /lobby to leave.");
    }

    /** Stops watching. With {@code toLobby} false the player is restored where they stand (they are about to play). */
    public void stop(UUID id, boolean toLobby) {
        Spectatable target = watching.remove(id);
        if (target == null) return;
        target.removeWatcher(id);
        plugin.getRegistry().release(id, this);
        if (avatars != null) avatars.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (toLobby) plugin.getSnapshots().restoreToLobby(player);
        else plugin.getSnapshots().restore(player);
    }

    private boolean move(Player player, Location destination) {
        moving.add(player.getUniqueId());
        try { return player.teleport(destination); }
        finally { moving.remove(player.getUniqueId()); }
    }

    @Override public void handleQuit(Player player) {
        // Keep the snapshot; it is restored on the next join.
        Spectatable target = watching.remove(player.getUniqueId());
        if (target != null) target.removeWatcher(player.getUniqueId());
        plugin.getRegistry().release(player.getUniqueId(), this);
        if (avatars != null) avatars.remove(player.getUniqueId());
    }

    @Override public void forceStop(String reason) {
        for (UUID id : List.copyOf(watching.keySet())) stop(id, true);
    }

    /** Watchers stay in the area they are watching. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) return;
        Spectatable target = watching.get(event.getPlayer().getUniqueId());
        if (target == null) return;
        Location to = event.getTo();
        boolean tooLow = to.getY() < to.getWorld().getMinHeight();
        if (tooLow || !target.contains(to)) {
            Location back = !tooLow && target.contains(event.getFrom()) ? event.getFrom() : target.spectatorSpawn();
            if (back != null) event.setTo(back);
        }
    }

    /** Watchers never take damage; one who slips into the void goes back to the spectator spawn. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Spectatable target = watching.get(player.getUniqueId());
        if (target == null) return;
        event.setCancelled(true);
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID && target.spectatorSpawn() != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> { if (watching.get(player.getUniqueId()) == target) move(player, target.spectatorSpawn()); });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (moving.contains(id)) return;
        Spectatable target = watching.get(id);
        if (target == null) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && !target.contains(event.getTo())) {
            event.setCancelled(true);
            Msg.error(event.getPlayer(), "Use /spectate to watch another match.");
        }
    }

    /** Watchers stay in Spectator mode; another plugin or command changing it ends the session cleanly. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (watching.containsKey(id) && event.getNewGameMode() != GameMode.SPECTATOR) {
            tasks.later(1, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null && watching.containsKey(id) && player.getGameMode() != GameMode.SPECTATOR) {
                    Msg.send(List.of(id), Msg.text("You stopped spectating.", DialogPalette.MUTED));
                    stop(id, true);
                }
            });
        }
    }
}
