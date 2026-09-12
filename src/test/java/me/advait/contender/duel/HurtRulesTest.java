package me.advait.contender.duel;

import me.advait.contender.kit.Kit;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.UUID;
import com.google.common.base.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HurtRulesTest {
    @Test @SuppressWarnings("deprecation")
    void preservesRawHitDamageAndBlockingWhileProtectingHealthAndAbsorption() {
        var values = new EnumMap<EntityDamageEvent.DamageModifier, Double>(EntityDamageEvent.DamageModifier.class);
        var functions = new EnumMap<EntityDamageEvent.DamageModifier, Function<? super Double, Double>>(EntityDamageEvent.DamageModifier.class);
        for (var modifier : EntityDamageEvent.DamageModifier.values()) { values.put(modifier, 0.0); functions.put(modifier, d -> 0.0); }
        values.put(EntityDamageEvent.DamageModifier.BASE, 12.0);
        values.put(EntityDamageEvent.DamageModifier.BLOCKING, -2.0);
        values.put(EntityDamageEvent.DamageModifier.ARMOR, -3.0);
        values.put(EntityDamageEvent.DamageModifier.ABSORPTION, -4.0);
        var event = new EntityDamageEvent(mock(Player.class), EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), values, functions);
        HurtRules.removeHealthDamage(event);
        assertFalse(event.isCancelled()); assertEquals(12, event.getDamage()); assertEquals(0, event.getFinalDamage());
        assertEquals(-2, event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING));
        assertEquals(0, event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
    }
    @Test void pvpAndPveSwitchesClassifyTheCausingPlayerAndKeepVoidLethal() {
        try (var server = new StateTestServer()) {
            Duel duel = mock(Duel.class); Kit kit = new Kit("sumo");
            when(duel.getPlugin()).thenReturn(server.plugin); when(duel.getKit()).thenReturn(kit);
            Player victim = mock(Player.class), attacker = mock(Player.class);
            UUID id = UUID.randomUUID(); when(victim.getUniqueId()).thenReturn(id); when(victim.getHealth()).thenReturn(20.0);
            when(duel.hasParticipant(id)).thenReturn(true);
            var state = new ActiveState(duel); when(duel.getState()).thenReturn(state); state.enable();
            var source = mock(DamageSource.class); when(source.getCausingEntity()).thenReturn(attacker);
            kit.setPvpHurt(true);
            for (var cause : new EntityDamageEvent.DamageCause[]{EntityDamageEvent.DamageCause.ENTITY_ATTACK, EntityDamageEvent.DamageCause.PROJECTILE, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION}) {
                var hit = new EntityDamageEvent(victim, cause, source, 40);
                state.onDamage(hit); assertEquals(0, hit.getFinalDamage()); assertFalse(hit.isCancelled());
            }
            verify(duel, never()).handleDeath(any(), any());
            var naturalSource = mock(DamageSource.class);
            var fall = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.FALL, naturalSource, 5);
            state.onDamage(fall); assertEquals(5, fall.getFinalDamage());
            kit.setPvpHurt(false); kit.setPveHurt(true);
            var zombie = mock(Zombie.class); when(naturalSource.getCausingEntity()).thenReturn(zombie);
            var mob = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, naturalSource, 30);
            state.onDamage(mob); assertEquals(0, mob.getFinalDamage()); assertFalse(mob.isCancelled());
            var hit = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.PROJECTILE, source, 5);
            state.onDamage(hit); assertEquals(5, hit.getFinalDamage());
            var voidHit = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.VOID, naturalSource, 1000);
            state.onDamage(voidHit); verify(duel).handleDeath(victim, null);
            state.disable();
        }
    }
    @Test void hungerAndExhaustionAreProtectedOnlyForThisDuelsHurtKitAndAdvancementsStayQuiet() {
        try (var server = new StateTestServer()) {
            Duel duel = mock(Duel.class); Kit kit = new Kit("sumo");
            when(duel.getPlugin()).thenReturn(server.plugin); when(duel.getKit()).thenReturn(kit);
            Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
            when(duel.hasParticipant(id)).thenReturn(true);
            var state = new SortingState(duel); when(duel.getState()).thenReturn(state); state.enable();
            var food = mock(FoodLevelChangeEvent.class); when(food.getEntity()).thenReturn(player);
            var exhaustion = mock(EntityExhaustionEvent.class); when(exhaustion.getEntity()).thenReturn(player);
            state.onHunger(food); state.onExhaustion(exhaustion);
            verify(food, never()).setFoodLevel(anyInt()); verify(exhaustion, never()).setCancelled(anyBoolean());
            kit.setPvpHurt(true); state.onHunger(food); state.onExhaustion(exhaustion);
            verify(food).setFoodLevel(20); verify(exhaustion).setCancelled(true);
            var advancement = mock(PlayerAdvancementDoneEvent.class); when(advancement.getPlayer()).thenReturn(player);
            state.onAdvancement(advancement); verify(advancement).message(null);
            clearInvocations(food, exhaustion, advancement);
            when(duel.hasParticipant(id)).thenReturn(false);
            state.onHunger(food); state.onExhaustion(exhaustion); state.onAdvancement(advancement);
            verify(food, never()).setFoodLevel(anyInt()); verify(exhaustion, never()).setCancelled(anyBoolean()); verify(advancement, never()).message(any());
            state.disable();
        }
    }
}
