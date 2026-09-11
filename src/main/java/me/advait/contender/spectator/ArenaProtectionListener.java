package me.advait.contender.spectator;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

/** Keeps each match's edits and movement inside its own copy. */
public final class ArenaProtectionListener implements Listener {
    private final DuelManager duels;
    private final ArenaManager arenas;
    public ArenaProtectionListener(DuelManager duels, ArenaManager arenas) { this.duels = duels; this.arenas = arenas; }

    private boolean combat(Location location) {
        ArenaInstance instance = arenas.at(location);
        if (instance == null || instance.status() != ArenaInstance.Status.IN_USE) return false;
        return duels.getActiveDuels().stream().anyMatch(d -> d.getArena() != null
                && d.getArena().instance() == instance && d.isCombatActive());
    }
    private boolean ownBlock(Player player, Location location) {
        Duel duel = duels.getDuel(player);
        return duel != null && duel.isCombatActive() && !duel.isSpectator(player.getUniqueId()) && duel.getMap().contains(location);
    }
    private boolean sameArena(Location first, Location second) {
        ArenaInstance arena = arenas.at(first);
        return arena != null && arena == arenas.at(second);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !ownBlock(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !ownBlock(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !ownBlock(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && (!ownBlock(event.getPlayer(), event.getBlock().getLocation())
                || !duels.getDuel(event.getPlayer()).getKit().isAllowBlockPlace())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && arenas.isArenaWorld(event.getClickedBlock().getWorld())
                && !ownBlock(event.getPlayer(), event.getClickedBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (arenas.isArenaWorld(event.getRightClicked().getWorld()) && !ownBlock(event.getPlayer(), event.getRightClicked().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) { contain(event); }
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { contain(event); }
    private void contain(PlayerMoveEvent event) {
        Duel duel = duels.getDuel(event.getPlayer());
        if (duel == null || duel.getArena() == null || !duel.getState().isEnabled() || duel.getState().isEnding()) return;
        if (event.getTo() != null && !duel.getMap().contains(event.getTo())) {
            Location safe = duel.getMap().contains(event.getFrom()) ? event.getFrom()
                    : duel.isSpectator(event.getPlayer().getUniqueId()) ? duel.getMap().getSpectatorSpawn()
                    : duel.getTeam(event.getPlayer().getUniqueId()) == duel.getTeam1() ? duel.getMap().getTeam1Spawn() : duel.getMap().getTeam2Spawn();
            if (safe != null) event.setTo(safe);
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && (!combat(event.getBlock().getLocation())
                || !sameArena(event.getBlock().getLocation(), event.getToBlock().getLocation()))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && (!combat(event.getBlock().getLocation())
                || !sameArena(event.getBlock().getLocation(), event.getBlock().getRelative(event.getDirection()).getLocation())
                || event.getBlocks().stream().anyMatch(b -> !sameArena(event.getBlock().getLocation(), b.getRelative(event.getDirection()).getLocation())))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && (!combat(event.getBlock().getLocation())
                || event.getBlocks().stream().anyMatch(b -> !sameArena(event.getBlock().getLocation(), b.getLocation())
                || !sameArena(event.getBlock().getLocation(), b.getRelative(event.getDirection()).getLocation())))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !combat(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !combat(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && (!combat(event.getBlock().getLocation())
                || !sameArena(event.getSource().getLocation(), event.getBlock().getLocation()))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !combat(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !combat(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (arenas.isArenaWorld(event.getLocation().getWorld())) {
            if (!combat(event.getLocation())) event.setCancelled(true);
            else event.blockList().removeIf(b -> !sameArena(event.getLocation(), b.getLocation()));
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld())) {
            if (!combat(event.getBlock().getLocation())) event.setCancelled(true);
            else event.blockList().removeIf(b -> !sameArena(event.getBlock().getLocation(), b.getLocation()));
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld()) && !combat(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player || !arenas.isArenaWorld(event.getEntity().getWorld())) return;
        ArenaInstance arena = arenas.at(event.getEntity().getLocation());
        if (arena == null || !arena.isReserved()) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (arenas.isArenaWorld(event.getEntity().getWorld()) && !combat(event.getLocation())) event.setCancelled(true);
    }
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (arenas.isArenaWorld(event.getLocation().getWorld()) && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) event.setCancelled(true);
    }
    @EventHandler public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState() && arenas.isArenaWorld(event.getWorld())) event.setCancelled(true);
    }
    @EventHandler public void onThunder(ThunderChangeEvent event) {
        if (event.toThunderState() && arenas.isArenaWorld(event.getWorld())) event.setCancelled(true);
    }
}
