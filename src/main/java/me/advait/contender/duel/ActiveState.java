package me.advait.contender.duel;

import me.advait.contender.util.MessageUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

public final class ActiveState extends AbstractDuelState {
    private long invincibilityEndTime;
    private BukkitTask invincibilityTask;

    public ActiveState(Duel duel) { super(duel); }

    @Override
    public boolean isCombatPhase() { return true; }

    @Override
    protected void onEnable() {
        duel.prepareRound();
        if (duel.getMode() == DuelMode.FFA) {
            invincibilityEndTime = System.currentTimeMillis() + 5000L;
            invincibilityTask = runRepeating(() -> {
                long remaining = invincibilityEndTime - System.currentTimeMillis();
                if (remaining <= 0) {
                    invincibilityTask.cancel();
                    duel.announceRound();
                    return;
                }
                int seconds = (int) Math.ceil(remaining / 1000.0);
                duel.broadcastActionBar("<color:" + MessageUtil.WARNING + ">Invincibility: <color:"
                        + MessageUtil.PRIMARY + ">" + seconds + "s</color></color>");
            }, 0L, 10L);
        } else {
            duel.announceRound();
        }
        duel.broadcastSound(Duel.SoundType.COUNTDOWN_GO);
    }

    public boolean isInvincibilityActive() {
        return isEnabled() && System.currentTimeMillis() < invincibilityEndTime;
    }

    @Override
    protected void handleDamage(EntityDamageEvent event, Player player) {
        if (isInvincibilityActive()) {
            event.setCancelled(true);
            return;
        }
        // Respect restrictions applied by earlier listeners before resolving an elimination.
        if (event.isCancelled()) return;
        if (player.getHealth() + player.getAbsorptionAmount() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            Player killer = null;
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Entity damager = byEntity.getDamager();
                if (damager instanceof Player attacker) killer = attacker;
                else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player attacker) {
                    killer = attacker;
                }
            }
            player.setHealth(player.getMaxHealth());
            player.setAbsorptionAmount(0);
            duel.handleDeath(player, killer);
            duel.spawnDeathMannequin(player);
        }
    }

    @Override
    protected void handleAttack(EntityDamageByEntityEvent event, Player attacker) {
        if (event.getEntity() instanceof Player victim) {
            if (duel.isSpectator(victim.getUniqueId())) {
                event.setCancelled(true);
            } else if (duel.getMode() != DuelMode.FFA) {
                DuelTeam attackerTeam = duel.getTeam(attacker.getUniqueId());
                if (attackerTeam != null && attackerTeam == duel.getTeam(victim.getUniqueId())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!owns(player) || duel.isSpectator(player.getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.deathMessage(null);
        event.setDroppedExp(0);
        duel.handleDeath(player, player.getKiller());
        duel.spawnDeathMannequin(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player) || !owns(player)) return;
        if (!duel.getKit().isNaturalRegen()
                && (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN)) {
            event.setCancelled(true);
        }
    }
}
