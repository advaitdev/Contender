package me.advait.contender.hacker;

import io.papermc.paper.registry.RegistryAccess;
import net.kyori.adventure.key.Key;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HackerAttributesTest {
    @Test void ownedModifiersStackWithTheWeaponAndCleanUpWithoutChangingOtherModifiers() {
        try (var access = mockStatic(RegistryAccess.class)) {
            access.when(RegistryAccess::registryAccess).thenReturn(mock(RegistryAccess.class, RETURNS_MOCKS));
            var attributes = new HashMap<Key, Attribute>();
            doAnswer(call -> attributes.computeIfAbsent(call.getArgument(0), key -> {
                Attribute attribute = mock(Attribute.class);
                when(attribute.getKey()).thenReturn(new NamespacedKey(key.namespace(), key.value())); return attribute;
            })).when(Registry.ATTRIBUTE).getOrThrow(any(Key.class));
            Player player = mock(Player.class);
            var modifiers = new HashMap<Attribute, Map<Key, AttributeModifier>>();
            var instances = new HashMap<Attribute, AttributeInstance>();
            when(player.getAttribute(any())).thenAnswer(call -> instances.computeIfAbsent(call.getArgument(0), key -> {
                var entries = new HashMap<Key, AttributeModifier>(); modifiers.put(key, entries);
                var instance = mock(AttributeInstance.class);
                when(instance.getModifier(any(Key.class))).thenAnswer(get -> entries.get(get.getArgument(0)));
                doAnswer(add -> { AttributeModifier modifier = add.getArgument(0); entries.put(modifier.getKey(), modifier); return null; }).when(instance).addTransientModifier(any());
                doAnswer(remove -> { entries.remove(((AttributeModifier) remove.getArgument(0)).getKey()); return null; }).when(instance).removeModifier(any(AttributeModifier.class));
                return instance;
            }));
            var settings = HackSettings.defaults().with(Map.of(HackSetting.REACH, 4.0, HackSetting.ATTACK_SPEED, 2.0,
                    HackSetting.ATTACK_DAMAGE, 1.5, HackSetting.ANTI_KNOCKBACK, 100.0));
            HackerAttributes.apply(player, settings);
            var speed = modifiers.get(Attribute.ATTACK_SPEED).values().iterator().next();
            assertEquals(1, speed.getAmount()); assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, speed.getOperation());
            assertEquals(1, modifiers.get(Attribute.ENTITY_INTERACTION_RANGE).values().iterator().next().getAmount());
            assertEquals(1, modifiers.get(Attribute.KNOCKBACK_RESISTANCE).values().iterator().next().getAmount());
            assertEquals(1, modifiers.get(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE).values().iterator().next().getAmount());
            var other = new AttributeModifier(new NamespacedKey("other", "bonus"), 0.5, AttributeModifier.Operation.ADD_NUMBER);
            modifiers.get(Attribute.ATTACK_SPEED).put(other.getKey(), other);
            HackerAttributes.apply(player, settings); verify(instances.get(Attribute.ATTACK_SPEED), times(1)).addTransientModifier(any());
            HackerAttributes.apply(player, null);
            assertEquals(Map.of(other.getKey(), other), modifiers.get(Attribute.ATTACK_SPEED));
            for (var instance : instances.values()) verify(instance, never()).setBaseValue(anyDouble());
        }
    }
}
