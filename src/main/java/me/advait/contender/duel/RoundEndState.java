package me.advait.contender.duel;

import me.advait.contender.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RoundEndState extends AbstractDuelState {
    private final DuelTeam winner;
    private final RoundResetProtection protection = new RoundResetProtection();
    private final Set<UUID> positioned = new HashSet<>();
    private final Map<UUID, Player> visitors = new HashMap<>();
    private UUID transporting;
    private Location transportDestination;
    private boolean resetting;
    private boolean resetComplete;
    private boolean arenaResetPending;
    private boolean arenaRestored;
    private boolean respawnReturnPending;
    private final DuelResetRetry recovery;

    public RoundEndState(Duel duel, DuelTeam winner) {
        super(duel);
        this.winner = winner;
        recovery = new DuelResetRetry(duel, (task, delay) -> runLater(task, delay));
    }

    @Override
    protected void onEnable() {
        if (winner != null) winner.incrementScore();
        int roundsToWin = duel.getTotalRounds() / 2 + 1;
        if (winner != null && winner.getScore() >= roundsToWin
                || duel.getCurrentRound() >= duel.getTotalRounds()) {
            runLater(duel::endDuel, 1L);
            return;
        }
        if (winner != null) {
            for (UUID uuid : winner.getPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) MessageUtil.playRoundWin(player);
            }
        }
        if (duel.getArena() == null) duel.rollbackArena(guard(this::startCountdown));
        // Leave the damage/death callback before restoring blocks and clearing its effects.
        else runLater(() -> checked(this::beginReset), 1L);
    }

    @Override public boolean canAddSpectator() { return duel.getArena() == null || resetComplete; }

    private void beginReset() {
        resetting = true;
        runRepeating(() -> checked(this::protectParticipants), 1L, 1L);
        attemptReset();
    }

    private void attemptReset() {
        if (!isEnabled() || !resetting || resetComplete || arenaResetPending) return;
        protectParticipants();
        if (arenaRestored) { returnForCountdown(); return; }
        arenaResetPending = true;
        try {
            duel.rollbackRoundArena(this::protectOccupant, guard(() -> {
                arenaResetPending = false;
                arenaRestored = true;
                checked(this::returnForCountdown);
            }));
        } catch (RuntimeException failure) {
            arenaResetPending = false;
            throw failure;
        }
    }

    @Override boolean recoverArenaResetFailure(Throwable failure) {
        arenaResetPending = false;
        return recoverResetFailure(failure);
    }

    @Override boolean recoverResetFailure(Throwable failure) {
        if (!isEnabled() || !resetting || duel.getArena() == null || resetComplete) return false;
        recovery.retry(failure, () -> checked(this::attemptReset));
        return true;
    }

    private void protectParticipants() {
        if (!resetting) return;
        for (UUID id : duel.getAllParticipants()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) protection.freeze(player);
        }
        for (Player visitor : List.copyOf(visitors.values())) {
            if (!visitor.isOnline() || !inside(visitor.getLocation())) participantLeaving(visitor);
        }
        var world = Bukkit.getWorld(duel.getMap().getWorldName());
        if (world != null) for (Player player : world.getPlayers()) {
            if (inside(player.getLocation())) protectOccupant(player);
        }
    }

    private boolean inside(Location location) {
        return location != null && location.getWorld() != null && duel.getArena() != null
                && location.getWorld().getName().equals(duel.getMap().getWorldName())
                && duel.getArena().instance().cell().containsColumn(location.getX(), location.getZ());
    }

    private boolean visitorHere(Player player) {
        return resetting && !duel.hasParticipant(player.getUniqueId()) && inside(player.getLocation());
    }

    private void protectOccupant(Player player) {
        if (!isEnabled() || !resetting || duel.getState() != this) throw new IllegalStateException("The round reset has stopped.");
        if (!duel.hasParticipant(player.getUniqueId())) visitors.put(player.getUniqueId(), player);
        protection.freeze(player);
    }

    private Location roundSpawn(UUID id) {
        Location spawn = duel.isInDuel(id)
                ? duel.getTeam(id) == duel.getTeam1() ? duel.getMap().getTeam1Spawn() : duel.getMap().getTeam2Spawn()
                : duel.getMap().getSpectatorSpawn();
        return spawn == null ? duel.getMap().getTeam1Spawn() : spawn;
    }

    private void returnForCountdown() {
        if (!isEnabled() || !resetting || resetComplete || !arenaRestored) return;
        boolean waitingForRespawn = false;
        for (UUID id : duel.getAllParticipants()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || positioned.contains(id)) continue;
            if (player.isDead()) { waitingForRespawn = true; continue; }
            transport(player, roundSpawn(id));
            if (!isEnabled()) return;
            positioned.add(id);
        }
        if (waitingForRespawn) {
            if (!respawnReturnPending) {
                respawnReturnPending = true;
                try {
                    runLater(() -> {
                        respawnReturnPending = false;
                        checked(this::returnForCountdown);
                    }, 1L);
                } catch (RuntimeException failure) {
                    respawnReturnPending = false;
                    throw failure;
                }
            }
            return;
        }
        for (Player visitor : List.copyOf(visitors.values())) {
            if (visitor.isOnline() && !visitor.isDead() && inside(visitor.getLocation())) {
                try { transport(visitor, roundSpawn(visitor.getUniqueId())); }
                catch (RuntimeException failure) {
                    plugin.getLogger().warning("Could not return arena visitor " + visitor.getName() + " to the spectator spawn: " + failure.getMessage());
                }
            }
        }
        protection.close();
        recovery.clear();
        visitors.clear();
        resetting = false;
        resetComplete = true;
        respawnReturnPending = false;
        positioned.clear();
        startCountdown();
    }

    private void transport(Player player, Location destination) {
        if (destination == null) throw new IllegalStateException("The duel's return spawn is missing.");
        transporting = player.getUniqueId();
        transportDestination = destination.clone();
        try {
            if (!player.teleport(destination.clone()) || !player.getWorld().equals(destination.getWorld())
                    || player.getLocation().distanceSquared(destination) > 1) {
                throw new IllegalStateException("Could not move " + player.getName() + " for the arena reset.");
            }
            player.setVelocity(new Vector());
            player.setFallDistance(0);
        } finally {
            transporting = null;
            transportDestination = null;
        }
    }

    @Override public boolean handleArenaContainment(PlayerMoveEvent event) {
        if (!resetting) return false;
        if (event instanceof PlayerTeleportEvent && event.getPlayer().getUniqueId().equals(transporting)
                && transportDestination.equals(event.getTo())) return true;
        if (event instanceof PlayerTeleportEvent) event.setCancelled(true);
        else if (event.hasChangedPosition()) {
            Location fixed = event.getFrom().clone();
            fixed.setYaw(event.getTo().getYaw()); fixed.setPitch(event.getTo().getPitch());
            event.setTo(fixed);
        }
        return true;
    }

    @Override protected Location respawnOverride(Player player) {
        if (!resetting) return null;
        protection.freeze(player);
        positioned.remove(player.getUniqueId());
        runLater(() -> {
            if (!owns(player)) return;
            if (resetting) protection.freeze(player);
            if (duel.isSpectator(player.getUniqueId())) duel.applyDeadSpectatorMode(player);
        }, 1L);
        Location spawn = roundSpawn(player.getUniqueId());
        return spawn == null ? null : spawn.clone();
    }

    @Override protected void participantLeaving(Player player) {
        protection.release(player);
        positioned.remove(player.getUniqueId());
        visitors.remove(player.getUniqueId());
    }
    @EventHandler public void releaseOnQuit(PlayerQuitEvent event) { participantLeaving(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVisitorMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !visitorHere(event.getPlayer())) return;
        protectOccupant(event.getPlayer());
        handleArenaContainment(event);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVisitorTeleporting(PlayerTeleportEvent event) {
        if (visitorHere(event.getPlayer()) && inside(event.getTo())) handleArenaContainment(event);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVisitorTeleport(PlayerTeleportEvent event) {
        if (!event.isCancelled() && visitors.containsKey(event.getPlayer().getUniqueId()) && !inside(event.getTo())) participantLeaving(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVisitorDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && visitorHere(player)) event.setCancelled(true);
    }
    @EventHandler public void resetInteraction(PlayerInteractEvent event) { if (resetting && (owns(event.getPlayer()) || visitorHere(event.getPlayer()))) event.setCancelled(true); }
    @EventHandler public void resetEntityInteraction(PlayerInteractEntityEvent event) { if (resetting && (owns(event.getPlayer()) || visitorHere(event.getPlayer()))) event.setCancelled(true); }
    @EventHandler public void resetDrop(PlayerDropItemEvent event) { if (resetting && (owns(event.getPlayer()) || visitorHere(event.getPlayer()))) event.setCancelled(true); }
    @EventHandler public void resetPickup(EntityPickupItemEvent event) {
        if (resetting && event.getEntity() instanceof Player player && (owns(player) || visitorHere(player))) event.setCancelled(true);
    }

    private void checked(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) { duel.resetFailed(failure); }
    }

    private void startCountdown() {
        for (int seconds = 3; seconds >= 1; seconds--) {
            int remaining = seconds;
            runLater(() -> {
                duel.broadcastCountdown(remaining);
                duel.broadcastSound(Duel.SoundType.COUNTDOWN_TICK);
            }, (4L - seconds) * 20L);
        }
        runLater(() -> duel.setState(new ActiveState(duel)), 80L);
    }

    @Override protected void onDisable() {
        resetting = false;
        respawnReturnPending = false;
        recovery.clear();
        try { protection.close(); }
        finally { visitors.clear(); duel.clearCountdown(); }
    }
}
