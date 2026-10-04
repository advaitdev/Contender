package me.advait.contender.pvp;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * PvP between players who are not in a game. Players in a game follow that game's rules, and
 * never hurt or get hurt by anyone outside it.
 */
public final class PvPListener implements Listener {
    private final Contender plugin;

    public PvPListener(Contender plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
        if (attacker == null || attacker.equals(victim)) return;
        Activity attackerGame = plugin.getRegistry().owner(attacker.getUniqueId());
        Activity victimGame = plugin.getRegistry().owner(victim.getUniqueId());
        if (attackerGame != null || victimGame != null) {
            if (attackerGame != victimGame || !plugin.getRegistry().isPlaying(attacker.getUniqueId())
                    || !plugin.getRegistry().isPlaying(victim.getUniqueId())) event.setCancelled(true);
            return;
        }
        PvPSettings settings = plugin.getPvpSettings();
        if (settings.isAdminPvpOverride() && attacker.hasPermission("contender.admin")) return;
        boolean lobby = plugin.getLobby().isLobbyWorld(victim.getWorld());
        if (lobby ? !settings.isAllowLobbyPvp() : !settings.isAllowNonDuelWorldPvp()) event.setCancelled(true);
    }
}
