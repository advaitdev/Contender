package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.arena.ArenaCopy;
import me.advait.contender.core.Module;
import me.advait.contender.role.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;
import java.util.function.Consumer;

/** Starts matches and routes every gameplay event to the match that owns the player. */
public final class DuelService extends Module {
    private final Map<Integer, Duel> duels = new LinkedHashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();

    public DuelService(Contender plugin) { super(plugin); }

    @Override protected void onDisable() {
        for (Duel duel : List.copyOf(duels.values())) duel.forceStop("The server is stopping.");
        duels.clear();
    }

    public Collection<Duel> duels() { return Collections.unmodifiableCollection(duels.values()); }

    /** The match this player is fighting in (not just watching), or null. */
    public Duel duelOf(UUID player) {
        Activity owner = plugin.getRegistry().owner(player);
        return owner instanceof Duel duel && duel.isPlayer(player) ? duel : null;
    }

    public boolean isFighting(UUID player) { return duelOf(player) != null; }

    /**
     * Starts a match. Every player must be an online contestant who is free (watching another match is fine;
     * they are pulled out of it). Throws with a readable message when anything is missing.
     */
    public Duel start(DuelSettings settings, List<List<UUID>> sides, List<String> names, String label, Consumer<DuelResult> onResult) {
        if (!isEnabled()) throw new IllegalStateException("Matches are not available right now.");
        if (plugin.getVotes().isActive()) throw new IllegalStateException("Wait for the vote to finish.");
        if (sides.size() < 2 || sides.stream().anyMatch(List::isEmpty)) throw new IllegalArgumentException("Each side needs at least one player.");
        Set<UUID> seen = new HashSet<>();
        for (List<UUID> side : sides) for (UUID id : side) {
            if (!seen.add(id)) throw new IllegalArgumentException("A player can only be on one side.");
            Player player = Bukkit.getPlayer(id);
            String name = player == null ? Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("A player") : player.getName();
            if (player == null) throw new IllegalStateException(name + " is offline.");
            if (plugin.getRoleManager().getRole(id) != PlayerRole.CONTESTANT) throw new IllegalStateException(name + " is not a contestant.");
            if (plugin.getRegistry().isPlaying(id)) throw new IllegalStateException(name + " is busy in " + plugin.getRegistry().owner(id).displayName() + ".");
            if (player.isDead()) throw new IllegalStateException(name + " must respawn first.");
        }
        if (!settings.map().isComplete()) throw new IllegalStateException("Set both team spawns for " + settings.map().getDisplayName() + " first.");
        // Watchers are pulled out of the match they are watching, without a trip to the lobby.
        for (UUID id : seen) if (plugin.getRegistry().isWatching(id)) plugin.getSpectate().stop(id, false);
        ArenaCopy arena = plugin.getArenas().lease(settings.map().getId(), null);
        if (arena == null) throw new IllegalStateException("No arena copy is free. " + plugin.getArenas().readiness(settings.map().getId()));
        Duel duel = null;
        try {
            duel = new Duel(plugin, this, settings, arena, sides, names, label, onResult);
            plugin.getArenas().bind(arena, duel);
            duels.put(duel.id(), duel);
            duel.start();
        } catch (RuntimeException failure) {
            if (duel != null) duels.remove(duel.id());
            plugin.getArenas().release(arena);
            throw failure;
        }
        plugin.getStages().refreshDisplays();
        return duel;
    }

    void closed(Duel duel) {
        duels.remove(duel.id());
        plugin.getStages().refreshDisplays();
    }

    void allowTeleport(UUID player) { teleporting.add(player); }
    void endTeleport(UUID player) { teleporting.remove(player); }

    // ---- Combat --------------------------------------------------------------------------------

    private static Player attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent hit) {
            if (hit.getDamager() instanceof Player player) return player;
            if (hit.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        }
        return event.getDamageSource().getCausingEntity() instanceof Player player ? player : null;
    }

    /** Players only hurt opponents in their own match, and only while it is being fought. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim) || attacker.equals(victim)) return;
        Duel attackerDuel = duelOf(attacker.getUniqueId()), victimDuel = duelOf(victim.getUniqueId());
        if (attackerDuel == null && victimDuel == null) return;
        if (attackerDuel != victimDuel || attackerDuel.phase() != Duel.Phase.FIGHTING || !attackerDuel.isAlive(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!attackerDuel.settings().freeForAll() && attackerDuel.team(attacker.getUniqueId()) == attackerDuel.team(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Runs last so armor, hacks and sabotages have already changed the damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Duel duel = duelOf(victim.getUniqueId());
        if (duel == null) return;
        if (duel.phase() != Duel.Phase.FIGHTING || !duel.isAlive(victim.getUniqueId())) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                tasks.later(1, () -> duel.teleport(victim, duel.phase() == Duel.Phase.FIGHTING ? duel.spectatorSpawn() : spawnFor(duel, victim)));
            }
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            duel.eliminate(victim, lastAttacker(victim));
            return;
        }
        boolean fromPlayer = attacker(event) != null;
        var kit = duel.kit();
        if (fromPlayer ? kit.isPvpHurt() : kit.isPveHurt()) {
            HurtRules.removeHealthDamage(event);
            return;
        }
        double remaining = victim.getHealth() + victim.getAbsorptionAmount() - event.getFinalDamage();
        if (remaining > 0) return;
        if (holdsTotem(victim)) return; // Let vanilla resolve the totem; a real death is handled below.
        event.setCancelled(true);
        Player killer = attacker(event);
        duel.eliminate(victim, killer == null ? lastAttacker(victim) : killer);
    }

    private Location spawnFor(Duel duel, Player player) {
        DuelTeam team = duel.team(player.getUniqueId());
        return team != null && team.number() == 2 ? duel.layout().getTeam2Spawn() : duel.layout().getTeam1Spawn();
    }

    private static boolean holdsTotem(Player player) {
        var inventory = player.getInventory();
        return inventory.getItemInMainHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING
                || inventory.getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING;
    }

    private static Player lastAttacker(Player victim) {
        var last = victim.getLastDamageCause();
        if (last != null && last.getDamageSource().getCausingEntity() instanceof Player player && !player.equals(victim)) return player;
        return victim.getKiller();
    }

    /** A real death only happens when a totem was held but could not save the player. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Duel duel = duelOf(player.getUniqueId());
        if (duel == null) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        duel.eliminate(player, player.getKiller());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelOf(player.getUniqueId());
        if (duel == null) return;
        event.setRespawnLocation(duel.spectatorSpawn());
        tasks.later(1, () -> {
            if (!player.isOnline() || duelOf(player.getUniqueId()) != duel) return;
            player.getInventory().clear();
            if (duel.isOver()) { player.setGameMode(GameMode.SPECTATOR); return; }
            player.setGameMode(GameMode.SPECTATOR);
        });
    }

    // ---- Movement ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition() || event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        Duel duel = duelOf(player.getUniqueId());
        if (duel == null) return;
        Location to = event.getTo();
        if (duel.frozen()) {
            Location held = event.getFrom().clone();
            held.setYaw(to.getYaw());
            held.setPitch(to.getPitch());
            event.setTo(held);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (!duel.arena().contains(to)) event.setTo(duel.spectatorSpawn());
            return;
        }
        var bounds = duel.layout().getBounds();
        if (duel.phase() == Duel.Phase.FIGHTING && duel.isAlive(player.getUniqueId()) && to.getY() < bounds.minY()) {
            duel.eliminate(player, lastAttacker(player));
            return;
        }
        if (!duel.arena().contains(to)) {
            Location back = event.getFrom().clone();
            if (!duel.arena().contains(back)) back = duel.spectatorSpawn();
            event.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (teleporting.contains(player.getUniqueId())) return;
        Duel duel = duelOf(player.getUniqueId());
        if (duel == null) return;
        switch (event.getCause()) {
            case ENDER_PEARL, CONSUMABLE_EFFECT, SPECTATE, DISMOUNT, EXIT_BED -> {
                if (duel.frozen() || !duel.arena().contains(event.getTo()) || duel.phase() == Duel.Phase.FIGHTING
                        && duel.isAlive(player.getUniqueId()) && !duel.layout().contains(event.getTo())) event.setCancelled(true);
            }
            default -> { }
        }
    }

    // ---- Items and interaction -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Duel duel = duelOf(event.getPlayer().getUniqueId());
        if (duel == null) return;
        if (duel.phase() != Duel.Phase.FIGHTING || !duel.isAlive(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Duel duel = duelOf(event.getPlayer().getUniqueId());
        if (duel != null && (duel.phase() != Duel.Phase.FIGHTING || event.getHand() == EquipmentSlot.OFF_HAND && duel.frozen())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        Duel duel = duelOf(player.getUniqueId());
        if (duel != null && (duel.phase() != Duel.Phase.FIGHTING || !duel.isAlive(player.getUniqueId()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        Duel duel = duelOf(event.getPlayer().getUniqueId());
        if (duel != null && (duel.phase() != Duel.Phase.FIGHTING || !duel.isAlive(event.getPlayer().getUniqueId()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelOf(player.getUniqueId());
        if (duel != null && (duel.phase() != Duel.Phase.FIGHTING || !duel.isAlive(player.getUniqueId()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelOf(player.getUniqueId());
        if (duel == null) return;
        if (duel.phase() != Duel.Phase.FIGHTING || duel.kit().keepsHungerFull()) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelOf(player.getUniqueId());
        if (duel != null && (duel.phase() != Duel.Phase.FIGHTING || duel.kit().keepsHungerFull())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelOf(player.getUniqueId());
        if (duel == null || duel.kit().isNaturalRegen()) return;
        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (duelOf(event.getPlayer().getUniqueId()) != null) event.message(null);
    }
}
