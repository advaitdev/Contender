package me.advait.contender.hacker;

import com.google.common.base.Function;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HackerResistanceTest {
    @Test @SuppressWarnings("deprecation") void reductionHappensBeforeAbsorptionWithoutRemovingRawHitDamage() {
        Player player = mock(Player.class); when(player.getAbsorptionAmount()).thenReturn(4.0);
        var values = new EnumMap<EntityDamageEvent.DamageModifier, Double>(EntityDamageEvent.DamageModifier.class);
        var functions = new EnumMap<EntityDamageEvent.DamageModifier, Function<? super Double, Double>>(EntityDamageEvent.DamageModifier.class);
        for (var modifier : EntityDamageEvent.DamageModifier.values()) { values.put(modifier, 0.0); functions.put(modifier, value -> 0.0); }
        values.put(EntityDamageEvent.DamageModifier.BASE, 12.0); values.put(EntityDamageEvent.DamageModifier.ARMOR, -2.0);
        values.put(EntityDamageEvent.DamageModifier.ABSORPTION, -4.0);
        var hit = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), values, functions);
        HackerResistance.apply(hit, player, 50);
        assertEquals(12, hit.getDamage()); assertEquals(1, hit.getFinalDamage());
        assertEquals(-4, hit.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION)); assertFalse(hit.isCancelled());
        HackerResistance.apply(hit, player, 100);
        assertEquals(12, hit.getDamage()); assertEquals(0, hit.getFinalDamage());
        assertEquals(0, hit.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION), 0.00001); assertFalse(hit.isCancelled());
    }
    @Test void invalidAndExtremeSettingsAreValidatedAndWarnedAbout() {
        assertThrows(IllegalArgumentException.class, () -> HackSettings.defaults().with(java.util.Map.of(HackSetting.REACH, Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> HackSettings.defaults().with(java.util.Map.of(HackSetting.MOVEMENT_SPEED, Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> HackSettings.defaults().with(java.util.Map.of(HackSetting.REACH, 65.0)));
        assertTrue(HackSettings.defaults().warnings().isEmpty());
        var settings = HackSettings.defaults().with(java.util.Map.of(HackSetting.REACH, 6.0, HackSetting.ANTI_KNOCKBACK, 100.0));
        assertEquals(java.util.List.of(HackSetting.REACH, HackSetting.ANTI_KNOCKBACK), settings.warnings());
    }
}
