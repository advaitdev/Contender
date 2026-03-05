package me.advait.contender.listener;

import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.duel.DuelState;
import me.advait.contender.duel.DuelTeam;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class DuelListener implements Listener {

    private final Contender plugin;
    private final DuelManager duelManager;

    public DuelListener(Contender plugin, DuelManager duelManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Duel duel = duelManager.getDuel(player);
        if (duel == null || duel.getState() != DuelState.ACTIVE) return;

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.deathMessage(null);
        event.setDroppedExp(0);
        event.getPlayer().setGameMode(GameMode.SPECTATOR);

        duel.handleDeath(player);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isDead()) {
                player.spigot().respawn();
            }
        }, 30L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        DuelTeam team = duel.getTeam(player.getUniqueId());
        if (team == null) return;

        if (team == duel.getTeam1() && duel.getMap().getTeam1Spawn() != null) {
            event.setRespawnLocation(duel.getMap().getTeam1Spawn());
        } else if (team == duel.getTeam2() && duel.getMap().getTeam2Spawn() != null) {
            event.setRespawnLocation(duel.getMap().getTeam2Spawn());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.getState() == DuelState.ACTIVE) {
            duel.handleDeath(player);
        } else if (duel.getState() == DuelState.SORTING || duel.getState() == DuelState.STARTING) {
            duel.forceEnd();
        }
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.getState() == DuelState.SORTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.isSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (duel.getState() != DuelState.ACTIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        Duel duel = duelManager.getDuel(attacker);
        if (duel == null) return;

        if (duel.isSpectator(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof Player victim) {
            Duel victimDuel = duelManager.getDuel(victim);
            if (victimDuel != null && victimDuel.isSpectator(victim.getUniqueId())) {
                event.setCancelled(true);
                return;
            }

            if (duel.getState() == DuelState.ACTIVE) {
                DuelTeam attackerTeam = duel.getTeam(attacker.getUniqueId());
                DuelTeam victimTeam = duel.getTeam(victim.getUniqueId());
                if (attackerTeam != null && attackerTeam == victimTeam) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelManager.getDuel(player);
        if (duel != null && duel.getState() != DuelState.ENDED) {
            event.setCancelled(true);
        }
    }
}
