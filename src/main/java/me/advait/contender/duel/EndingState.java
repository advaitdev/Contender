package me.advait.contender.duel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps participants protected and the arena reserved until rollback completes. */
public final class EndingState extends AbstractDuelState {
    private final boolean forced;
    private final RoundResetProtection protection = new RoundResetProtection();
    private final Map<UUID, Player> visitors = new HashMap<>();
    private boolean resetting;
    private UUID transporting;

    public EndingState(Duel duel, boolean forced) {
        super(duel);
        this.forced = forced;
    }

    boolean isForced() { return forced; }

    @Override
    public boolean isEnding() { return true; }

    @Override
    public boolean canAddSpectator() { return false; }

    @Override
    protected void onEnable() {
        if (forced) {
            cleanUp();
        } else {
            duel.announceResult();
            runLater(this::cleanUp, 60L);
        }
    }

    private void cleanUp() {
        duel.returnParticipantsToLobby();
        if (duel.getArena() == null) {
            duel.rollbackArena(guard(duel::finish));
            return;
        }
        checked(() -> {
            resetting = true;
            protectVisitors();
            runRepeating(() -> checked(this::protectVisitors), 1L, 1L);
            duel.rollbackRoundArena(this::protect, guard(() -> checked(this::finishReset)));
        });
    }

    private boolean inside(Location location) {
        return location != null && location.getWorld() != null && duel.getArena() != null
                && location.getWorld().getName().equals(duel.getMap().getWorldName())
                && duel.getArena().instance().cell().containsColumn(location.getX(), location.getZ());
    }

    private void protect(Player player) {
        if (!isEnabled() || !resetting) throw new IllegalStateException("Arena reset was cancelled.");
        protection.freeze(player);
        visitors.put(player.getUniqueId(), player);
    }

    private void protectVisitors() {
        if (!resetting) return;
        for (Player player : List.copyOf(visitors.values())) {
            if (!player.isOnline() || !inside(player.getLocation())) release(player);
        }
        var world = Bukkit.getWorld(duel.getMap().getWorldName());
        if (world != null) for (Player player : world.getPlayers()) {
            if (inside(player.getLocation())) protect(player);
        }
    }

    private void finishReset() {
        Location spawn = duel.getMap().getSpectatorSpawn();
        if (spawn == null) spawn = duel.getMap().getTeam1Spawn();
        for (Player player : List.copyOf(visitors.values())) {
            if (!player.isOnline() || player.isDead() || !inside(player.getLocation())) continue;
            if (spawn == null) {
                plugin.getLogger().warning("Could not return " + player.getName() + " after the arena reset: spectator spawn is missing.");
                continue;
            }
            transporting = player.getUniqueId();
            try {
                if (!player.teleport(spawn.clone()) || !player.getWorld().equals(spawn.getWorld())
                        || player.getLocation().distanceSquared(spawn) > 1) {
                    plugin.getLogger().warning("Could not return " + player.getName() + " to the spectator spawn after the arena reset.");
                }
                protection.freeze(player);
            } finally { transporting = null; }
        }
        protection.close();
        visitors.clear();
        resetting = false;
        duel.finish();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void resetMove(PlayerMoveEvent event) { containVisitor(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void resetTeleport(PlayerTeleportEvent event) { containVisitor(event); }

    private void containVisitor(PlayerMoveEvent event) {
        if (!resetting || !inside(event.getFrom())) return;
        if (event instanceof PlayerTeleportEvent) {
            if (!inside(event.getTo()) || event.getPlayer().getUniqueId().equals(transporting)) return;
            event.setCancelled(true);
        } else if (event.hasChangedPosition()) {
            Location fixed = event.getFrom().clone();
            fixed.setYaw(event.getTo().getYaw()); fixed.setPitch(event.getTo().getPitch());
            event.setTo(fixed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void resetTeleported(PlayerTeleportEvent event) {
        if (!event.isCancelled() && resetting && !inside(event.getTo())) release(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void resetDamage(EntityDamageEvent event) {
        if (resetting && event.getEntity() instanceof Player player && inside(player.getLocation())) event.setCancelled(true);
    }

    @EventHandler public void resetQuit(PlayerQuitEvent event) { release(event.getPlayer()); }

    private void release(Player player) {
        protection.release(player);
        visitors.remove(player.getUniqueId());
    }

    private void checked(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) { duel.resetFailed(failure); }
    }

    @Override protected void onDisable() {
        resetting = false;
        protection.close();
        visitors.clear();
    }
}
