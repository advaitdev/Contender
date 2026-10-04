package me.advait.contender.sabotage;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.types.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER;
import static org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1;

/** Every built-in sabotage. Add a new one here and it appears in the menus and sabotages.yml. */
public final class Sabotages {
    private Sabotages() { }

    /** Built but switched off for now: they don't appear anywhere. Remove an id to bring it back. */
    static final java.util.Set<String> DISABLED = java.util.Set.of("sugar_rush", "rusty_swords", "pogo", "spotlight", "vampire",
            "fragile", "switcheroo", "nameless", "blackout", "double_health", "glass_cannon");

    public static Map<String, Sabotage> all(LagInjector lag) {
        List<Sabotage> list = List.of(
                new LagSpike(lag),
                new AttributeSabotage("double_health", "Double Health", "Everyone gets twice the hearts.", DialogIcon.HEART, true,
                        new AttributeSabotage.Change(Attribute.MAX_HEALTH, 1, MULTIPLY_SCALAR_1)),
                new AttributeSabotage("glass_cannon", "Glass Cannon", "Hits deal double damage, but everyone has half the hearts.", DialogIcon.TNT, false,
                        new AttributeSabotage.Change(Attribute.ATTACK_DAMAGE, 1, MULTIPLY_SCALAR_1),
                        new AttributeSabotage.Change(Attribute.MAX_HEALTH, -0.5, MULTIPLY_SCALAR_1)),
                new AttributeSabotage("tiny", "Tiny Fighters", "Everyone shrinks to half size. Good luck hitting anyone.", DialogIcon.SPYGLASS, false,
                        new AttributeSabotage.Change(Attribute.SCALE, -0.5, MULTIPLY_SCALAR_1)),
                new AttributeSabotage("giants", "Giants", "Everyone grows half again as tall, with longer arms.", DialogIcon.STAR, false,
                        new AttributeSabotage.Change(Attribute.SCALE, 0.5, MULTIPLY_SCALAR_1),
                        new AttributeSabotage.Change(Attribute.ENTITY_INTERACTION_RANGE, 1, ADD_NUMBER),
                        new AttributeSabotage.Change(Attribute.BLOCK_INTERACTION_RANGE, 1, ADD_NUMBER)),
                new AttributeSabotage("moon_gravity", "Moon Gravity", "Gravity drops. Every hit sends people flying.", DialogIcon.PHANTOM, false,
                        new AttributeSabotage.Change(Attribute.GRAVITY, -0.6, MULTIPLY_SCALAR_1),
                        new AttributeSabotage.Change(Attribute.SAFE_FALL_DISTANCE, 12, ADD_NUMBER)),
                new AttributeSabotage("heavy_hits", "Heavy Hits", "Every hit knocks people much further.", DialogIcon.WIND, false,
                        new AttributeSabotage.Change(Attribute.ATTACK_KNOCKBACK, 1.5, ADD_NUMBER)),
                new AttributeSabotage("sugar_rush", "Sugar Rush", "Everyone moves a third faster.", DialogIcon.SUGAR, false,
                        new AttributeSabotage.Change(Attribute.MOVEMENT_SPEED, 0.35, MULTIPLY_SCALAR_1)),
                new AttributeSabotage("rusty_swords", "Rusty Swords", "Weapons take much longer to recharge.", DialogIcon.RUSTY, false,
                        new AttributeSabotage.Change(Attribute.ATTACK_SPEED, -0.4, MULTIPLY_SCALAR_1)),
                new AttributeSabotage("pogo", "Pogo", "Everyone jumps twice as high and takes less fall damage.", DialogIcon.JUMP, false,
                        new AttributeSabotage.Change(Attribute.JUMP_STRENGTH, 0.6, MULTIPLY_SCALAR_1),
                        new AttributeSabotage.Change(Attribute.SAFE_FALL_DISTANCE, 6, ADD_NUMBER)),
                new EffectSabotage("spotlight", "Spotlight", "Everyone glows through walls.", DialogIcon.GLOW, PotionEffectType.GLOWING, 0),
                new Vampire(),
                new Fragile(),
                new Butterfingers(),
                new Switcheroo(),
                new Nameless(),
                new Blackout(),
                new Famished());
        Map<String, Sabotage> byId = new LinkedHashMap<>();
        for (Sabotage sabotage : list) if (!DISABLED.contains(sabotage.id())) byId.put(sabotage.id(), sabotage);
        return byId;
    }
}
