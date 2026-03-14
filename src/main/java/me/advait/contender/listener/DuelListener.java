package me.advait.contender.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import me.advait.contender.Contender;
import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.duel.DuelMode;
import me.advait.contender.duel.DuelState;
import me.advait.contender.duel.DuelTeam;
import me.advait.contender.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.*;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

public class DuelListener implements Listener {

    private final Contender plugin;
    private final DuelManager duelManager;

    public DuelListener(Contender plugin, DuelManager duelManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
    }

    private void spawnDeathMannequin(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        Mannequin mannequin = (Mannequin) world.spawnEntity(loc, EntityType.MANNEQUIN);
        mannequin.setInvisible(true);

        // copy skin
        mannequin.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));

        // copy facing
        mannequin.teleport(loc);
        mannequin.setRotation(loc.getYaw(), loc.getPitch());

        // optional: make it not interact / move
        mannequin.setImmovable(true);
        mannequin.setGravity(false);
        mannequin.setInvisible(false);

        // show all normal player model parts if you want
        // mannequin.setModelPartShown(PlayerModelPart.CAPE, true);
        // mannequin.setModelPartShown(PlayerModelPart.HAT, true);
        // etc.

        // play fake death animation
        mannequin.damage(100);

        // remove it shortly after
        new BukkitRunnable() {
            @Override
            public void run() {
                if (mannequin.isValid()) mannequin.remove();
            }
        }.runTaskLater(plugin, 60L);
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
        // handleDeath applies the disguise-spectator mode; also mark as dead-spectator before respawn
        duel.handleDeath(player, player.getKiller());
        spawnDeathMannequin(player);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        // Dead-player-spectator (real death path): respawn at spectator spawn and re-apply disguise
        if (duel.isSpectator(player.getUniqueId())) {
            if (duel.getMap().getSpectatorSpawn() != null) {
                event.setRespawnLocation(duel.getMap().getSpectatorSpawn());
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> duel.applyDeadSpectatorMode(player), 1L);
            return;
        }

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

        // Spectators leaving don't affect the duel
        if (duel.isSpectator(player.getUniqueId())) return;

        if (duel.getState() == DuelState.ENDED) return;

        // Broadcast disconnect notice to all participants before ending
        Component msg = MiniMessage.miniMessage().deserialize(
                MessageUtil.FONT_OPEN + "<color:" + MessageUtil.ERROR + ">" +
                player.getName() + " disconnected — duel cancelled.</color>" + MessageUtil.FONT_CLOSE);
        for (UUID uuid : duel.getAllParticipants()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && !p.equals(player)) p.sendMessage(msg);
        }

        duel.forceEnd();
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

    @EventHandler(priority = EventPriority.HIGH)
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
            return;
        }

        if (duel.isInvincibilityActive()) {
            event.setCancelled(true);
            return;
        }

        // Intercept lethal damage — prevent actual death and handle it ourselves
        if (player.getHealth() + player.getAbsorptionAmount() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);

            Player killer = null;
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Entity damager = byEntity.getDamager();
                if (damager instanceof Player p) {
                    killer = p;
                } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
                    killer = p;
                }
            }

            player.setHealth(player.getMaxHealth());
            player.setAbsorptionAmount(0);
            duel.handleDeath(player, killer);
            spawnDeathMannequin(player);
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

            if (duel.getState() == DuelState.ACTIVE && duel.getMode() != DuelMode.FFA) {
                DuelTeam attackerTeam = duel.getTeam(attacker.getUniqueId());
                DuelTeam victimTeam = duel.getTeam(victim.getUniqueId());
                if (attackerTeam != null && attackerTeam == victimTeam) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onUseMobSpawnEgg(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!(event.getItem() != null && event.getItem().getType().toString().contains("SPAWN_EGG"))) return;

        Duel duel = duelManager.getDuel(player);
        if (duel == null) return;

        if (duel.getState() != DuelState.ACTIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        Duel duel = duelManager.getDuel(player);
        if (duel == null || !duel.isSpectator(player.getUniqueId())) return;
        // Prevent spectators from landing/disabling fly
        if (!event.isFlying()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Duel duel = duelManager.getDuel(player);
        if (duel == null || duel.getState() != DuelState.ACTIVE) return;

        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
            if (!duel.getKit().isNaturalRegen()) {
                event.setCancelled(true);
            }
        }
    }

}
