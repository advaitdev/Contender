package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaActivity;
import me.advait.contender.arena.ArenaCopy;
import me.advait.contender.duel.HurtRules;
import me.advait.contender.duel.SpawnRing;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * A minigame fought in one arena copy with a kit: last man standing, king of the hill, winner stays on.
 * Handles the arena lease, spawning, equipment, and combat rules; games decide what a kill means.
 */
public abstract class ArenaGame extends Minigame implements ArenaActivity {
    protected final ArenaMap map;
    protected final Kit kit;
    protected ArenaCopy arena;
    private final Map<UUID, Long> spawnProtection = new HashMap<>();
    private final Set<Long> placedBlocks = new HashSet<>();

    @Override public Kit kit() { return kit; }

    protected ArenaGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, ArenaMap map, Kit kit) {
        super(plugin, type, id, name, players);
        this.map = map;
        this.kit = kit;
    }

    /** A player was killed (or fell into the void). {@code killer} may be null. */
    protected abstract void killed(Player victim, Player killer);

    /** Whether this player may deal and take damage right now. */
    protected boolean fighting(Player player) {
        return state == State.RUNNING && isPlaying(player.getUniqueId()) && player.getGameMode() != GameMode.SPECTATOR;
    }

    @Override protected CompletableFuture<Void> prepare() {
        if (map == null || !map.isComplete()) return CompletableFuture.failedFuture(new IllegalStateException("The map needs both team spawns."));
        arena = plugin.getArenas().lease(map.getId(), this);
        if (arena == null) return CompletableFuture.failedFuture(new IllegalStateException("No arena copy is free. " + plugin.getArenas().readiness(map.getId())));
        return CompletableFuture.completedFuture(null);
    }

    @Override protected void cleanup() {
        if (arena != null) plugin.getArenas().release(arena);
        arena = null;
    }

    @Override public Location spectatorSpawn() {
        if (arena == null) return null;
        Location spawn = arena.layout().getSpectatorSpawn();
        return spawn == null ? arena.layout().getTeam1Spawn() : spawn;
    }

    @Override public boolean contains(Location location) { return arena != null && arena.contains(location); }

    @Override public Location focus() { return arena == null ? null : middle().add(0, 1, 0); }


    /** Spawn points around the middle of the arena, one per player. */
    /**
     * At least {@code count} spawn spots: the map's FFA spawns in a random order (repeated when there are more
     * players than spawns), or a ring through both team spawns when the map has none. Picking one at random
     * picks a random FFA spawn.
     */
    protected List<Location> spawnRing(int count) {
        return spawnSpots(arena.layout(), count);
    }

    public static List<Location> spawnSpots(ArenaMap layout, int count) {
        List<Location> ffa = layout.getFfaSpawns();
        if (ffa.isEmpty()) return SpawnRing.around(layout.getTeam1Spawn(), layout.getTeam2Spawn(), Math.max(2, count));
        List<Location> spots = new ArrayList<>();
        while (spots.size() < Math.max(1, count)) {
            List<Location> round = new ArrayList<>(ffa);
            Collections.shuffle(round);
            for (Location spot : round) spots.add(spot.clone());
        }
        return spots;
    }

    /** The middle of the arena between the two team spawns. */
    protected Location middle() {
        Location first = arena.layout().getTeam1Spawn(), second = arena.layout().getTeam2Spawn();
        Location middle = first.clone().add(second).multiply(0.5);
        middle.setWorld(first.getWorld());
        middle.setY(Math.min(first.getY(), second.getY()));
        return middle;
    }

    /** Puts a player in play: teleport, survival, kit, full health. */
    protected void deploy(Player player, Location spawn) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        if (player.isInsideVehicle()) player.leaveVehicle();
        player.teleport(spawn);
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.closeInventory();
        kit.apply(player);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20 : max.getValue());
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setFireTicks(0);
        protect(player, 60);
    }

    /** Brief protection after (re)spawning, so nobody gets spawn-killed. */
    protected void protect(Player player, int ticks) {
        spawnProtection.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
    }

    private boolean isProtected(Player player) {
        Long until = spawnProtection.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** Spectator mode in place, as a watcher inside the game. */
    protected void bench(Player player) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        // Knocked off the map or below its floor: watch from the spectator spawn instead of under the arena.
        if (offMap(player.getLocation()) && spectatorSpawn() != null) player.teleport(watchSpot());
        else player.setVelocity(new Vector(0, 0.3, 0));
    }

    @Override protected boolean offMap(Location at) {
        return arena != null && (!arena.insideMap(at) || at.getY() < arena.layout().getBounds().minY() + 1);
    }

    // ---- ArenaActivity -------------------------------------------------------------------------

    @Override public boolean allowBlockChange(Player player, Location at, boolean placing) {
        if (!fighting(player)) return false;
        long key = ((long) at.getBlockX() & 0x3FFFFFF) << 38 | ((long) at.getBlockZ() & 0x3FFFFFF) << 12 | (at.getBlockY() & 0xFFF);
        if (placing) {
            if (!kit.isAllowBlockPlace()) return false;
            placedBlocks.add(key);
            return true;
        }
        return kit.isAllowBlockBreak() && placedBlocks.remove(key);
    }

    @Override public boolean environmentActive() { return state == State.RUNNING; }

    // ---- Combat --------------------------------------------------------------------------------

    private boolean ours(Player player) { return plugin.getRegistry().owner(player.getUniqueId()) == this; }

    private static Player attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent hit) {
            if (hit.getDamager() instanceof Player player) return player;
            if (hit.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        }
        return event.getDamageSource().getCausingEntity() instanceof Player player ? player : null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim) || attacker.equals(victim)) return;
        if (!ours(attacker) && !ours(victim)) return;
        if (!ours(attacker) || !ours(victim) || !fighting(attacker) || !fighting(victim) || isProtected(victim)) { event.setCancelled(true); return; }
        if (isProtected(attacker)) spawnProtection.remove(attacker.getUniqueId());
        if (!canHit(attacker, victim)) event.setCancelled(true);
    }

    /** Who last hit each player, and when, so a fall long after a hit isn't credited to anyone. */
    private final Map<UUID, Map.Entry<UUID, Long>> lastHits = new HashMap<>();
    private static final long KNOCK_OFF_MILLIS = 10_000;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitLanded(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event);
        if (attacker == null || !(event.getEntity() instanceof Player victim) || attacker.equals(victim) || !ours(victim)) return;
        lastHits.put(victim.getUniqueId(), Map.entry(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    /** Whoever hit this player in the last few seconds, for a knock-off into the void. */
    private Player knocker(Player victim) {
        var hit = lastHits.get(victim.getUniqueId());
        if (hit == null || System.currentTimeMillis() - hit.getValue() > KNOCK_OFF_MILLIS) return null;
        return Bukkit.getPlayer(hit.getKey());
    }

    /**
     * Below this height a player has fallen off the map: the map's void level when it has one, otherwise the bottom
     * of its saved region (never lower, since nobody can leave the region).
     */
    protected double voidLevel() {
        double bottom = arena.layout().getBounds().minY();
        Double set = arena.layout().getVoidY();
        return set == null ? bottom : Math.max(bottom, set);
    }

    /** Whether the map sets its own void level. */
    protected boolean mapSetsVoid() { return arena.layout().getVoidY() != null; }

    /** A player fell into the void; {@code knocker} hit them last, or is null. It counts as a death unless a game says otherwise. */
    protected void fell(Player player, Player knocker) { killed(player, knocker); }

    /** Hits still knock players around, but nobody loses hearts or hunger (the void is handled on its own). */
    protected boolean knockbackOnly() { return false; }

    /** Team rules. Everyone can hit everyone by default. */
    protected boolean canHit(Player attacker, Player victim) { return true; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !ours(victim)) return;
        if (!fighting(victim)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) tasks.later(1, () -> victim.teleport(watchSpot()));
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) { event.setCancelled(true); fell(victim, knocker(victim)); return; }
        if (isProtected(victim)) { event.setCancelled(true); return; }
        boolean fromPlayer = attacker(event) != null;
        if (knockbackOnly() || (fromPlayer ? kit.isPvpHurt() : kit.isPveHurt())) { HurtRules.removeHealthDamage(event); return; }
        // Final damage is already reduced by absorption.
        if (victim.getHealth() - event.getFinalDamage() > 0) return;
        if (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
        event.setCancelled(true);
        Player killer = attacker(event);
        killed(victim, killer == null ? lastAttacker(victim) : killer);
    }

    private static Player lastAttacker(Player victim) {
        var last = victim.getLastDamageCause();
        if (last != null && last.getDamageSource().getCausingEntity() instanceof Player player && !player.equals(victim)) return player;
        return victim.getKiller();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!ours(player)) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        // Revive instead of dying, so nobody is left on the respawn screen.
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        event.setReviveHealth(max == null ? 20 : max.getValue());
        event.setCancelled(true);
        if (fighting(player)) killed(player, player.getKiller());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!ours(event.getPlayer())) return;
        Location spawn = watchSpot();
        if (spawn != null) event.setRespawnLocation(spawn);
        Player player = event.getPlayer();
        tasks.later(1, () -> { if (player.isOnline() && ours(player) && !fighting(player)) player.setGameMode(GameMode.SPECTATOR); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        if (!ours(player) || arena == null) return;
        if (state == State.COUNTDOWN && player.getGameMode() != GameMode.SPECTATOR) {
            Location held = event.getFrom().clone();
            held.setYaw(event.getTo().getYaw());
            held.setPitch(event.getTo().getPitch());
            event.setTo(held);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (!arena.contains(event.getTo())) event.setTo(watchSpot());
            return;
        }
        if (fighting(player) && event.getTo().getY() < voidLevel()) { fell(player, knocker(player)); return; }
        if (!arena.contains(event.getTo())) event.setTo(arena.contains(event.getFrom()) ? event.getFrom() : watchSpot());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!ours(event.getPlayer()) || arena == null) return;
        switch (event.getCause()) {
            case ENDER_PEARL, CONSUMABLE_EFFECT, SPECTATE -> { if (!arena.contains(event.getTo())) event.setCancelled(true); }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (ours(event.getPlayer()) && !fighting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        if (ours(event.getPlayer()) && !fighting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && ours(player) && !fighting(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && ours(player) && (!fighting(player) || knockbackOnly() || kit.keepsHungerFull())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player) || !ours(player) || kit.isNaturalRegen()) return;
        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) { if (ours(event.getPlayer())) event.message(null); }

    // ---- Persistence ---------------------------------------------------------------------------

    @Override protected void writeExtra(org.bukkit.configuration.ConfigurationSection section) {
        section.set("map", map == null ? null : map.getId());
        section.set("kit", kit == null ? null : kit.getId());
    }

    protected static List<UUID> online(Collection<UUID> players) {
        List<UUID> online = new ArrayList<>();
        for (UUID id : players) if (Bukkit.getPlayer(id) != null) online.add(id);
        return online;
    }
}
