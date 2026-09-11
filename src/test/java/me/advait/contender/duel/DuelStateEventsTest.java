package me.advait.contender.duel;

import me.advait.contender.testutil.StateTestServer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuelStateEventsTest {
    private Duel context(StateTestServer server) {
        Duel duel = mock(Duel.class);
        when(duel.getPlugin()).thenReturn(server.plugin);
        when(duel.getMode()).thenReturn(DuelMode.STANDARD);
        return duel;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    @Test
    void eliminatedContestantsStillForfeitOnDisconnectButVisitorsDoNot() {
        try (var server = new StateTestServer()) {
            Duel duel = context(server);
            Player contestant = player(), visitor = player();
            when(contestant.getName()).thenReturn("Alice");
            when(duel.hasParticipant(any())).thenReturn(true);
            when(duel.isSpectator(any())).thenReturn(true);
            when(duel.isInDuel(contestant.getUniqueId())).thenReturn(true);
            ActiveState state = new ActiveState(duel);
            when(duel.getState()).thenReturn(state);
            state.enable();
            PlayerQuitEvent contestantQuit = mock(PlayerQuitEvent.class);
            when(contestantQuit.getPlayer()).thenReturn(contestant);
            state.onQuit(contestantQuit);
            verify(duel).forfeit(contestant.getUniqueId());
            PlayerQuitEvent visitorQuit = mock(PlayerQuitEvent.class);
            when(visitorQuit.getPlayer()).thenReturn(visitor);
            state.onQuit(visitorQuit);
            verify(duel, never()).forfeit(visitor.getUniqueId());
            state.disable();
        }
    }

    @Test
    void sortingOnlyProtectsItsOwnPlayersAndStopsHandlingEventsOnExit() {
        try (var server = new StateTestServer()) {
            Duel duel = context(server);
            Player owner = player();
            Player other = player();
            when(duel.hasParticipant(owner.getUniqueId())).thenReturn(true);
            SortingState state = new SortingState(duel);
            when(duel.getState()).thenReturn(state);
            state.enable();
            EntityDamageEvent ownDamage = mock(EntityDamageEvent.class);
            EntityDamageEvent otherDamage = mock(EntityDamageEvent.class);
            when(ownDamage.getEntity()).thenReturn(owner);
            when(otherDamage.getEntity()).thenReturn(other);
            state.onDamage(ownDamage);
            state.onDamage(otherDamage);
            verify(ownDamage).setCancelled(true);
            verify(otherDamage, never()).setCancelled(anyBoolean());
            state.disable();
            PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
            when(drop.getPlayer()).thenReturn(owner);
            state.onItemDrop(drop);
            verify(drop, never()).setCancelled(anyBoolean());
        }
    }

    @Test
    void cancelledLethalDamageDoesNotEliminateThePlayer() {
        try (var server = new StateTestServer()) {
            Duel duel = context(server);
            Player victim = player();
            when(duel.hasParticipant(victim.getUniqueId())).thenReturn(true);
            ActiveState state = new ActiveState(duel);
            when(duel.getState()).thenReturn(state);
            state.enable();
            EntityDamageEvent damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(victim);
            when(damage.isCancelled()).thenReturn(true);
            state.onDamage(damage);
            verify(duel, never()).handleDeath(any(), any());
            verify(victim, never()).setHealth(anyDouble());
            state.disable();
        }
    }

    @Test
    void projectileFriendlyFireAndAttacksIntoAnotherDuelAreBlocked() {
        try (var server = new StateTestServer()) {
            Duel duel = context(server);
            Player attacker = player();
            Player teammate = player();
            Player outsider = player();
            when(duel.hasParticipant(attacker.getUniqueId())).thenReturn(true);
            when(duel.hasParticipant(teammate.getUniqueId())).thenReturn(true);
            DuelTeam team = new DuelTeam("Team 1");
            when(duel.getTeam(attacker.getUniqueId())).thenReturn(team);
            when(duel.getTeam(teammate.getUniqueId())).thenReturn(team);
            ActiveState state = new ActiveState(duel);
            when(duel.getState()).thenReturn(state);
            state.enable();
            Projectile arrow = mock(Projectile.class);
            when(arrow.getShooter()).thenReturn(attacker);
            EntityDamageByEntityEvent friendlyFire = mock(EntityDamageByEntityEvent.class);
            when(friendlyFire.getDamager()).thenReturn(arrow);
            when(friendlyFire.getEntity()).thenReturn(teammate);
            state.onAttack(friendlyFire);
            verify(friendlyFire).setCancelled(true);
            EntityDamageByEntityEvent otherDuel = mock(EntityDamageByEntityEvent.class);
            when(otherDuel.getDamager()).thenReturn(attacker);
            when(otherDuel.getEntity()).thenReturn(outsider);
            state.onAttack(otherDuel);
            verify(otherDuel).setCancelled(true);
            state.disable();
        }
    }
}
