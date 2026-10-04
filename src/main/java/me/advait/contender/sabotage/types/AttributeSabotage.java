package me.advait.contender.sabotage.types;

import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.sabotage.Sabotage;
import me.advait.contender.sabotage.SabotageContext;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.List;

/** Changes attributes with temporary modifiers, so kits, hacks and other plugins keep their own. */
public class AttributeSabotage implements Sabotage {
    public record Change(Attribute attribute, double amount, AttributeModifier.Operation operation) { }

    private final String id, name, description;
    private final DialogIcon icon;
    private final List<Change> changes;
    private final boolean refill;

    /** @param refill heal to the new maximum when applied (for health boosts) */
    public AttributeSabotage(String id, String name, String description, DialogIcon icon, boolean refill, Change... changes) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.refill = refill;
        this.changes = List.of(changes);
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public DialogIcon icon() { return icon; }

    private NamespacedKey key(Change change) {
        return new NamespacedKey("contender", "sabotage_" + id + "_" + change.attribute().getKey().getKey());
    }

    @Override public void apply(Player player, SabotageContext context) {
        boolean changed = false;
        for (Change change : changes) {
            var attribute = player.getAttribute(change.attribute());
            if (attribute == null) continue;
            NamespacedKey key = key(change);
            if (attribute.getModifier(key) != null) continue;
            attribute.addTransientModifier(new AttributeModifier(key, change.amount(), change.operation()));
            changed = true;
        }
        if (changed && refill) {
            var max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && !player.isDead()) player.setHealth(max.getValue());
        }
    }

    @Override public void remove(Player player, SabotageContext context) {
        for (Change change : changes) {
            var attribute = player.getAttribute(change.attribute());
            if (attribute == null) continue;
            var modifier = attribute.getModifier(key(change));
            if (modifier != null) attribute.removeModifier(modifier);
        }
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && !player.isDead() && player.getHealth() > max.getValue()) player.setHealth(max.getValue());
    }
}
