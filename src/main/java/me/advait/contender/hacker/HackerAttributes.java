package me.advait.contender.hacker;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import java.util.List;
import static org.bukkit.attribute.AttributeModifier.Operation.*;

/** Transient, owned modifiers leave kit, potion and other plugin modifiers intact. */
public final class HackerAttributes {
    private record Boost(Attribute attribute, HackSetting setting, double scale, double offset, AttributeModifier.Operation operation) {
        NamespacedKey key() { return new NamespacedKey("contender", "hack_" + attribute.getKey().getKey()); }
    }
    private static List<Boost> boosts() {
        return List.of(
                new Boost(Attribute.ENTITY_INTERACTION_RANGE, HackSetting.REACH, 1, -3, ADD_NUMBER),
                new Boost(Attribute.ATTACK_SPEED, HackSetting.ATTACK_SPEED, 1, -1, MULTIPLY_SCALAR_1),
                new Boost(Attribute.ATTACK_DAMAGE, HackSetting.ATTACK_DAMAGE, 1, -1, MULTIPLY_SCALAR_1),
                new Boost(Attribute.KNOCKBACK_RESISTANCE, HackSetting.ANTI_KNOCKBACK, 0.01, 0, ADD_NUMBER),
                new Boost(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, HackSetting.ANTI_KNOCKBACK, 0.01, 0, ADD_NUMBER),
                new Boost(Attribute.MOVEMENT_SPEED, HackSetting.MOVEMENT_SPEED, 1, -1, MULTIPLY_SCALAR_1),
                new Boost(Attribute.JUMP_STRENGTH, HackSetting.JUMP_STRENGTH, 1, -1, MULTIPLY_SCALAR_1),
                new Boost(Attribute.STEP_HEIGHT, HackSetting.STEP_HEIGHT, 1, -0.6, ADD_NUMBER));
    }
    public static void apply(Player player, HackSettings settings) {
        for (var boost : boosts()) {
            var attribute = player.getAttribute(boost.attribute());
            if (attribute == null) continue;
            var existing = attribute.getModifier(boost.key());
            double amount = settings == null ? 0 : settings.get(boost.setting()) * boost.scale() + boost.offset();
            if (existing != null && amount == existing.getAmount() && boost.operation() == existing.getOperation()) continue;
            if (existing != null) attribute.removeModifier(existing);
            if (amount != 0) attribute.addTransientModifier(new AttributeModifier(boost.key(), amount, boost.operation()));
        }
    }
    private HackerAttributes() { }
}
