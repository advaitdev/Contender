package me.advait.contender.listener;

import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.duel.DuelState;
import me.advait.contender.util.MessageUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

public class ArenaProtectionListener implements Listener {

    private final DuelManager duelManager;

    public ArenaProtectionListener(DuelManager duelManager) {
        this.duelManager = duelManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (duel.getState() != DuelState.ACTIVE) {
            event.setCancelled(true);
            return;
        }

        if (!duel.getKit().isAllowBlockPlace()) {
            event.setCancelled(true);
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">Block placement is disabled for this kit!</color>");
            return;
        }

        duel.addPlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (duel.getState() != DuelState.ACTIVE) {
            event.setCancelled(true);
            return;
        }

        if (!duel.getKit().isAllowBlockBreak()) {
            event.setCancelled(true);
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">Block breaking is disabled for this kit!</color>");
            return;
        }

        if (!duel.isPlacedBlock(event.getBlock().getLocation())) {
            event.setCancelled(true);
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">You can only break player-placed blocks!</color>");
            return;
        }

        duel.removePlacedBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (duel.getState() != DuelState.ACTIVE) {
            event.setCancelled(true);
            return;
        }

        if (!duel.getKit().isAllowBlockPlace()) {
            event.setCancelled(true);
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.ERROR + ">Block placement is disabled for this kit!</color>");
        }
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        if (hasDuelInWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
            return;
        }
        if (hasDuelInWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        if (hasDuelInWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (hasDuelInWorld(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (hasDuelInWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (hasDuelInWorld(event.getBlock().getWorld())) {
            if (!(event.getEntity() instanceof Player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) {
            return;
        }
        if (hasDuelInWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState() && hasDuelInWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onThunderChange(ThunderChangeEvent event) {
        if (event.toThunderState() && hasDuelInWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    private boolean hasDuelInWorld(World world) {
        String worldName = world.getName();
        for (Duel duel : duelManager.getActiveDuels()) {
            if (duel.getMap().getWorldName().equals(worldName)
                    && duel.getState() != DuelState.ENDED) {
                return true;
            }
        }
        return false;
    }
}
