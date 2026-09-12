package me.advait.contender.duel;

import me.advait.contender.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class RoundEndState extends AbstractDuelState {
    private final DuelTeam winner;
    private final Set<UUID> parked = new HashSet<>();
    private Location holdingLocation;
    private UUID transporting;
    private Location transportDestination;
    private boolean holding;
    private boolean resetComplete;
    private int respawnWait;

    public RoundEndState(Duel duel, DuelTeam winner) {
        super(duel);
        this.winner = winner;
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
        // Leave the damage/death callback before moving players or clearing its effects.
        else runLater(() -> checked(this::beginReset), 1L);
    }

    @Override public boolean canAddSpectator() { return duel.getArena() == null || resetComplete; }

    private void beginReset() {
        holdingLocation = duel.getPlugin().getLobbyManager().getLobbyLocation();
        if (holdingLocation == null || holdingLocation.getWorld() == null
                || duel.getPlugin().getArenaManager().isArenaWorld(holdingLocation.getWorld())) {
            throw new IllegalStateException("Set a lobby outside the arena world before resetting this match.");
        }
        holding = true;
        evacuate();
    }

    private void evacuate() {
        boolean waitingForRespawn = false;
        for (UUID id : duel.getAllParticipants()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || parked.contains(id)) continue;
            if (player.isDead()) { waitingForRespawn = true; continue; }
            transport(player, holdingLocation);
            if (!isEnabled()) return;
            parked.add(id);
        }
        if (waitingForRespawn) {
            if (++respawnWait >= 600) throw new IllegalStateException("A player did not respawn before the arena reset.");
            runLater(() -> checked(this::evacuate), 1L);
            return;
        }
        duel.rollbackArena(guard(() -> checked(this::returnForCountdown)));
    }

    private void returnForCountdown() {
        for (UUID id : duel.getAllParticipants()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            Location destination = duel.isInDuel(id)
                    ? duel.getTeam(id) == duel.getTeam1() ? duel.getMap().getTeam1Spawn() : duel.getMap().getTeam2Spawn()
                    : duel.getMap().getSpectatorSpawn();
            if (destination == null) destination = duel.getMap().getTeam1Spawn();
            transport(player, destination);
            if (!isEnabled()) return;
        }
        holding = false;
        resetComplete = true;
        parked.clear();
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
        if (!holding) return false;
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

    @Override protected Location respawnOverride(Player player) { return holding ? holdingLocation.clone() : null; }

    @EventHandler public void holdInteraction(PlayerInteractEvent event) { if (holding && owns(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void holdEntityInteraction(PlayerInteractEntityEvent event) { if (holding && owns(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void holdDrop(PlayerDropItemEvent event) { if (holding && owns(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void holdPickup(EntityPickupItemEvent event) {
        if (holding && event.getEntity() instanceof Player player && owns(player)) event.setCancelled(true);
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

    @Override protected void onDisable() { duel.clearCountdown(); }
}
