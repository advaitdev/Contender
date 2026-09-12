package me.advait.contender.duel;

import me.advait.contender.game.AbstractGameState;
import me.advait.contender.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

/** Shared participant rules; each instance belongs to exactly one duel. */
public abstract class AbstractDuelState extends AbstractGameState {

    protected final Duel duel;

    protected AbstractDuelState(Duel duel) {
        super(duel.getPlugin());
        this.duel = duel;
    }

    public boolean isCombatPhase() { return false; }
    public boolean isEnding() { return false; }
    public boolean isFinished() { return false; }
    public boolean canAddSpectator() { return true; }
    /** Return true when this phase has handled transport or movement itself. */
    public boolean handleArenaContainment(org.bukkit.event.player.PlayerMoveEvent event) { return false; }
    protected Location respawnOverride(Player player) { return null; }
    protected void participantLeaving(Player player) { }

    protected final boolean owns(Player player) {
        return isEnabled() && duel.getState() == this && duel.hasParticipant(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !owns(player)) return;
        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        handleDamage(event, player);
    }

    protected void handleDamage(EntityDamageEvent event, Player player) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public final void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        if (owns(event.getPlayer())) event.message(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onHunger(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && owns(player) && duel.getKit().keepsHungerFull()) {
            event.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public final void onExhaustion(org.bukkit.event.entity.EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && owns(player) && duel.getKit().keepsHungerFull()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public final void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player ? player : null;
        if (attacker == null || !owns(attacker)) return;
        if (duel.isSpectator(attacker.getUniqueId())
                || event.getEntity() instanceof Player victim && !owns(victim)) {
            event.setCancelled(true);
            return;
        }
        handleAttack(event, attacker);
    }

    protected void handleAttack(EntityDamageByEntityEvent event, Player attacker) {
        event.setCancelled(true);
    }

    @EventHandler
    public final void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!owns(player)) return;
        Location override = respawnOverride(player);
        if (override != null) {
            event.setRespawnLocation(override);
            return;
        }
        if (duel.isSpectator(player.getUniqueId())) {
            if (duel.getMap().getSpectatorSpawn() != null) {
                event.setRespawnLocation(duel.getMap().getSpectatorSpawn());
            }
            runLater(() -> {
                if (owns(player) && duel.isSpectator(player.getUniqueId())) duel.applyDeadSpectatorMode(player);
            }, 1L);
            return;
        }
        DuelTeam team = duel.getTeam(player.getUniqueId());
        if (team == null) return;
        var spawn = team == duel.getTeam1() ? duel.getMap().getTeam1Spawn() : duel.getMap().getTeam2Spawn();
        if (spawn != null) event.setRespawnLocation(spawn);
    }

    @EventHandler
    public final void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!owns(player) || !duel.isInDuel(player.getUniqueId()) || isEnding()) return;
        Component message = MiniMessage.miniMessage().deserialize(
                "<color:" + MessageUtil.ERROR + ">" + player.getName()
                        + " disconnected.</color>");
        duel.broadcastMessage(message);
        duel.forfeit(player.getUniqueId());
    }

    @EventHandler
    public final void onSpawnEgg(PlayerInteractEvent event) {
        if (!owns(event.getPlayer()) || isCombatPhase()) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getItem() != null
                && event.getItem().getType().name().endsWith("_SPAWN_EGG")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public final void onToggleFlight(PlayerToggleFlightEvent event) {
        if (owns(event.getPlayer()) && duel.isSpectator(event.getPlayer().getUniqueId()) && !event.isFlying()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!owns(player)) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (!isCombatPhase()) {
            event.setCancelled(true);
            return;
        }

        if (!duel.getKit().isAllowBlockPlace()) {
            event.setCancelled(true);
            return;
        }

        duel.addPlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!owns(player)) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (!isCombatPhase()) {
            event.setCancelled(true);
            return;
        }

        if (!duel.getKit().isAllowBlockBreak()) {
            event.setCancelled(true);
            return;
        }

        if (!duel.isPlacedBlock(event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }

        duel.removePlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (!owns(player)) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (!isCombatPhase()) {
            event.setCancelled(true);
            return;
        }

        if (!duel.getKit().isAllowBlockPlace()) {
            event.setCancelled(true);
        }
    }
}
