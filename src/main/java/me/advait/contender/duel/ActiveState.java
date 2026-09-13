package me.advait.contender.duel;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class ActiveState extends AbstractDuelState {
    private long invincibilityEndTime;
    private final DuelResetRetry retry;
    private org.bukkit.scheduler.BukkitTask holdingTask;
    private boolean ready;

    public ActiveState(Duel duel) {
        super(duel);
        retry = new DuelResetRetry(duel, (action, delay) -> runLater(action, delay), "round preparation");
    }

    @Override
    public boolean isCombatPhase() { return ready; }

    @Override public boolean canAddSpectator() { return ready; }

    @Override
    protected void onEnable() { prepare(); }

    private void prepare() {
        try { duel.prepareRound(); }
        catch (RuntimeException failure) {
            if (holdingTask == null) holdingTask = runRepeating(() -> { if (!ready) duel.holdPreparingPlayers(); }, 1L, 1L);
            duel.holdPreparingPlayers();
            retry.retry(failure, this::prepare);
            return;
        }
        retry.clear();
        if (holdingTask != null) holdingTask.cancel();
        ready = true;
        if (duel.getMode() == DuelMode.FFA) {
            invincibilityEndTime = System.currentTimeMillis() + 5000L;
        }
        try { duel.broadcastSound(Duel.SoundType.COUNTDOWN_GO); }
        catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not play the duel start sound.", failure);
        }
    }

    public boolean isInvincibilityActive() {
        return isEnabled() && ready && System.currentTimeMillis() < invincibilityEndTime;
    }

    @Override public boolean handleArenaContainment(org.bukkit.event.player.PlayerMoveEvent event) {
        return !ready && duel.holdPreparationMovement(event);
    }

    @Override protected void onDisable() { ready = false; retry.clear(); }

    @Override
    protected void handleDamage(EntityDamageEvent event, Player player) {
        if (!ready || isInvincibilityActive()) {
            event.setCancelled(true);
            return;
        }
        // Respect restrictions applied by earlier listeners before resolving an elimination.
        if (event.isCancelled()) return;
        boolean pvp = event.getDamageSource().getCausingEntity() instanceof Player;
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID
                && (pvp ? duel.getKit().isPvpHurt() : duel.getKit().isPveHurt())) {
            // Keep the hit event so Paper applies its normal hurt animation and knockback.
            HurtRules.removeHealthDamage(event);
            return;
        }
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
        if (!ready) { event.setCancelled(true); return; }
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
        if (!ready) return;
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
