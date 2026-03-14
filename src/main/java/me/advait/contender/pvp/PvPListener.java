package me.advait.contender.pvp;

import me.advait.contender.duel.Duel;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.spectator.SpectatorManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

public class PvPListener implements Listener {

    private final PvPSettings pvpSettings;
    private final DuelManager duelManager;
    private final SpectatorManager spectatorManager;

    public PvPListener(PvPSettings pvpSettings, DuelManager duelManager, SpectatorManager spectatorManager) {
        this.pvpSettings = pvpSettings;
        this.duelManager = duelManager;
        this.spectatorManager = spectatorManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // Resolve the attacking player (direct hit or projectile)
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) return;

        UUID attackerUuid = attacker.getUniqueId();
        UUID victimUuid = victim.getUniqueId();

        // Admins bypass all PvP restrictions when the override is enabled
        if (pvpSettings.isAdminPvpOverride() && attacker.hasPermission("contender.admin")) return;

        // If the attacker is an active duel contestant, DuelListener handles it
        Duel attackerDuel = duelManager.getDuel(attackerUuid);
        if (attackerDuel != null && !attackerDuel.isSpectator(attackerUuid)) return;

        // If the victim is an active duel contestant, DuelListener handles it
        Duel victimDuel = duelManager.getDuel(victimUuid);
        if (victimDuel != null && !victimDuel.isSpectator(victimUuid)) return;

        // Determine if both players are lobby players (not in any duel role)
        boolean attackerIsLobby = !isInAnyDuelRole(attackerUuid);
        boolean victimIsLobby = !isInAnyDuelRole(victimUuid);

        // Rule 1: Lobby PvP — block if both players are lobby players and setting is off
        if (!pvpSettings.isAllowLobbyPvp() && attackerIsLobby && victimIsLobby) {
            event.setCancelled(true);
            return;
        }

        // Rule 2: Non-duel world PvP — block if the victim's world has no active duel
        if (!pvpSettings.isAllowNonDuelWorldPvp()) {
            String worldName = victim.getWorld().getName();
            boolean worldHasActiveDuel = false;
            for (Duel duel : duelManager.getActiveDuels()) {
                if (duel.getMap().getWorldName().equals(worldName)) {
                    worldHasActiveDuel = true;
                    break;
                }
            }
            if (!worldHasActiveDuel) {
                event.setCancelled(true);
            }
        }
    }

    /** Returns true if the player is in any duel-related role (contestant or spectator). */
    private boolean isInAnyDuelRole(UUID uuid) {
        if (duelManager.getDuel(uuid) != null) return true;
        if (spectatorManager.isDeceased(uuid)) return true;
        return false;
    }
}
