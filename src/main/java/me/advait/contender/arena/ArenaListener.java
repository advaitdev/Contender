package me.advait.contender.arena;

import me.advait.contender.Contender;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;

/**
 * Keeps the arena world tidy: block changes stay inside the copy that is in use, idle copies never change,
 * and every change marks its copy dirty so the next reset pastes the template again.
 */
public final class ArenaListener implements Listener {
    private final Contender plugin;
    private final ArenaService arenas;

    public ArenaListener(Contender plugin, ArenaService arenas) {
        this.plugin = plugin;
        this.arenas = arenas;
    }

    private ArenaCopy live(Location location) {
        ArenaCopy copy = arenas.copyAt(location);
        return copy != null && copy.status() == ArenaCopy.Status.IN_USE && copy.user() != null ? copy : null;
    }

    private void playerChange(Player player, Block block, boolean placing, Cancellable event) {
        if (!arenas.isArenaWorld(block.getWorld())) return;
        ArenaCopy copy = live(block.getLocation());
        if (copy == null || !copy.insideMap(block.getLocation()) || plugin.getRegistry().owner(player.getUniqueId()) != (Object) copy.user()
                || !copy.user().allowBlockChange(player, block.getLocation(), placing)) {
            event.setCancelled(true);
            return;
        }
        copy.markDirty();
    }

    private boolean environment(Location from, Location to) {
        ArenaCopy copy = live(from);
        return copy != null && copy.user().environmentActive() && copy.insideMap(from) && (to == null || copy.insideMap(to));
    }

    private void environmentChange(Location from, Location to, Cancellable event) {
        if (!arenas.isArenaWorld(from.getWorld())) return;
        if (!environment(from, to)) { event.setCancelled(true); return; }
        arenas.copyAt(from).markDirty();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { playerChange(event.getPlayer(), event.getBlockPlaced(), true, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { playerChange(event.getPlayer(), event.getBlock(), false, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { playerChange(event.getPlayer(), event.getBlock(), true, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { playerChange(event.getPlayer(), event.getBlock(), false, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) { playerChange(event.getPlayer(), event.getBlock(), true, event); }

    /** Doors, trapdoors, levers and similar blocks change state; mark the copy so it resets. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !arenas.isArenaWorld(block.getWorld())) return;
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL || block.getType().isInteractable()) {
            ArenaCopy copy = arenas.copyAt(block.getLocation());
            if (copy != null) copy.markDirty();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) { environmentChange(event.getBlock().getLocation(), event.getToBlock().getLocation(), event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { environmentChange(event.getBlock().getLocation(), null, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!arenas.isArenaWorld(event.getBlock().getWorld())) return;
        if (event.getPlayer() != null) playerChange(event.getPlayer(), event.getBlock(), true, event);
        else environmentChange(event.getBlock().getLocation(), null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) { environmentChange(event.getSource().getLocation(), event.getBlock().getLocation(), event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) { environmentChange(event.getBlock().getLocation(), null, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) { environmentChange(event.getBlock().getLocation(), null, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) { environmentChange(event.getBlock().getLocation(), null, event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTree(StructureGrowEvent event) {
        if (arenas.isArenaWorld(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent event) {
        if (arenas.isArenaWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (!arenas.isArenaWorld(event.getBlock().getWorld())) return;
        if (event.getEntity() instanceof Player player) playerChange(player, event.getBlock(), false, event);
        else environmentChange(event.getBlock().getLocation(), null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!arenas.isArenaWorld(event.getBlock().getWorld())) return;
        for (Block moved : event.getBlocks()) {
            if (!environment(event.getBlock().getLocation(), moved.getRelative(event.getDirection()).getLocation())) { event.setCancelled(true); return; }
        }
        environmentChange(event.getBlock().getLocation(), event.getBlock().getRelative(event.getDirection()).getLocation(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!arenas.isArenaWorld(event.getBlock().getWorld())) return;
        for (Block moved : event.getBlocks()) {
            if (!environment(event.getBlock().getLocation(), moved.getLocation())) { event.setCancelled(true); return; }
        }
        environmentChange(event.getBlock().getLocation(), null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!arenas.isArenaWorld(event.getLocation().getWorld())) return;
        explode(event.getLocation(), event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!arenas.isArenaWorld(event.getBlock().getWorld())) return;
        explode(event.getBlock().getLocation(), event.blockList());
    }

    /** Explosions keep their knockback; only the blocks they may destroy are filtered. */
    private void explode(Location center, List<Block> blocks) {
        ArenaCopy copy = live(center);
        if (copy == null || !copy.user().environmentActive()) { blocks.clear(); return; }
        blocks.removeIf(block -> !copy.insideMap(block.getLocation()));
        if (!blocks.isEmpty()) copy.markDirty();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!arenas.isArenaWorld(event.getLocation().getWorld())) return;
        switch (event.getSpawnReason()) {
            case NATURAL, CHUNK_GEN, REINFORCEMENTS, PATROL, RAID, TRAP, JOCKEY, MOUNT, VILLAGE_DEFENSE, VILLAGE_INVASION -> event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState() && arenas.isArenaWorld(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler public void onThunder(ThunderChangeEvent event) {
        if (event.toThunderState() && arenas.isArenaWorld(event.getWorld())) event.setCancelled(true);
    }

    /** Anyone in the arena world without a game there (for example after a crash) is sent back to the lobby. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) { checkStray(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> checkStray(player), 10L);
    }

    private void checkStray(Player player) {
        if (!player.isOnline() || !arenas.isArenaWorld(player.getWorld())) return;
        if (!plugin.getRegistry().isFree(player.getUniqueId())) return;
        if (player.hasPermission("contender.master") && player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && arenas.isArenaWorld(player.getWorld()) && plugin.getRegistry().isFree(player.getUniqueId())) {
                if (plugin.getSnapshots().has(player.getUniqueId())) plugin.getSnapshots().restoreToLobby(player);
                else plugin.getLobby().send(player);
            }
        });
    }
}
