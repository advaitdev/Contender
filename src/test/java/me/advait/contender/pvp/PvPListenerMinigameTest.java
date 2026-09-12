package me.advait.contender.pvp;

import me.advait.contender.duel.DuelManager;
import me.advait.contender.minigame.MinigameManager;
import me.advait.contender.role.RoleManager;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PvPListenerMinigameTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameEventHitsPassWhenLobbyAndNonDuelPvpAreDisabled(boolean projectile) {
        var settings = mock(PvPSettings.class);
        when(settings.isAllowLobbyPvp()).thenReturn(false);
        when(settings.isAllowNonDuelWorldPvp()).thenReturn(false);
        var minigames = mock(MinigameManager.class);
        Player attacker = player(), victim = player();
        when(minigames.owns(attacker.getUniqueId())).thenReturn(true);
        when(minigames.owns(victim.getUniqueId())).thenReturn(true);
        when(minigames.sameEvent(attacker.getUniqueId(), victim.getUniqueId())).thenReturn(true);
        var listener = new PvPListener(settings, mock(DuelManager.class), mock(RoleManager.class), minigames);
        var hit = hit(attacker, victim, projectile);

        listener.onDamage(hit);

        assertFalse(hit.isCancelled());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void differentEventHitsAreBlockedEvenWithAdminOverride(boolean projectile) {
        var settings = mock(PvPSettings.class);
        when(settings.isAdminPvpOverride()).thenReturn(true);
        when(settings.isAllowLobbyPvp()).thenReturn(true);
        when(settings.isAllowNonDuelWorldPvp()).thenReturn(true);
        var minigames = mock(MinigameManager.class);
        Player attacker = player(), victim = player();
        when(attacker.hasPermission("contender.admin")).thenReturn(true);
        when(minigames.owns(attacker.getUniqueId())).thenReturn(true);
        when(minigames.owns(victim.getUniqueId())).thenReturn(true);
        when(minigames.sameEvent(attacker.getUniqueId(), victim.getUniqueId())).thenReturn(false);
        var listener = new PvPListener(settings, mock(DuelManager.class), mock(RoleManager.class), minigames);
        var hit = hit(attacker, victim, projectile);

        listener.onDamage(hit);

        assertTrue(hit.isCancelled());
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var world = mock(World.class);
        when(world.getName()).thenReturn("contender_arenas");
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    private static EntityDamageByEntityEvent hit(Player attacker, Player victim, boolean projectile) {
        Entity damager = attacker;
        if (projectile) {
            Arrow arrow = mock(Arrow.class);
            when(arrow.getShooter()).thenReturn(attacker);
            damager = arrow;
        }
        return new EntityDamageByEntityEvent(damager, victim,
                projectile ? DamageCause.PROJECTILE : DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 8);
    }
}
