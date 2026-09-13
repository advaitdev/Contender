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

/** Keeps edits inside the template and contestants inside their arena's reserved space. */
public final class ArenaProtectionListener implements Listener {
    private final DuelManager duels;
    private final ArenaManager arenas;
    private final me.advait.contender.race.RaceManager races;
    public ArenaProtectionListener(DuelManager duels, ArenaManager arenas) { this(duels, arenas, null); }

    public ArenaProtectionListener(DuelManager duels, ArenaManager arenas, me.advait.contender.race.RaceManager races) { this.duels = duels; this.arenas = arenas; this.races = races; }

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
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) { contain(event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { contain(event); }
    private void contain(PlayerMoveEvent event) {
        if (arenas.isPreparingEntry(event.getFrom(), event.getTo())) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof PlayerTeleportEvent teleport && duels.isSpectatorTransfer(teleport)) return;
        Duel duel = duels.getDuel(event.getPlayer());
        if (duel == null || duel.getArena() == null || !duel.getState().isEnabled() || duel.getState().isEnding()) return;
        if (duel.getState().handleArenaContainment(event)) return;
        Location to = event.getTo();
        if (to == null || duel.getMap().contains(to)) return;
        Location from = event.getFrom();
        var cell = duel.getArena().instance().cell();
        if (!(event instanceof PlayerTeleportEvent) && duel.isCombatActive()
                && !duel.isSpectator(event.getPlayer().getUniqueId())
                && to.getWorld().equals(from.getWorld())
                && to.getWorld().getName().equals(duel.getMap().getWorldName())
                && cell.containsColumn(from.getX(), from.getZ())) {
            if ((duel.getKit().isPvpHurt() || duel.getKit().isPveHurt())
                    && duel.getMap().getBounds() != null && to.getY() < duel.getMap().getBounds().minY()) {
                var spawn = duel.getMap().getSpectatorSpawn();
                event.setTo(spawn == null ? duel.getMap().getTeam1Spawn() : spawn);
                duel.handleDeath(event.getPlayer(), null);
                return;
            }
            // The empty margin lets knockback carry players off the platform. The cell has
            // no movement floor, so ordinary kits can also fall all the way into the void.
            if (cell.containsColumn(to.getX(), to.getZ())) return;
        }
        Location safe = duel.getMap().contains(from) ? from
                : duel.isSpectator(event.getPlayer().getUniqueId()) ? duel.getMap().getSpectatorSpawn()
                : duel.getTeam(event.getPlayer().getUniqueId()) == duel.getTeam1() ? duel.getMap().getTeam1Spawn() : duel.getMap().getTeam2Spawn();
        if (safe != null) event.setTo(safe);
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
            if (races != null && races.inArena(event.getLocation())) event.blockList().clear();
            else if (!combat(event.getLocation())) event.setCancelled(true);
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
