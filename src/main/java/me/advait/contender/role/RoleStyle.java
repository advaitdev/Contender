package me.advait.contender.role;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;

public record RoleStyle(String prefix, NamedTextColor color) {
    public RoleStyle {
        if (prefix == null || prefix.length() > 32 || prefix.codePoints().anyMatch(Character::isISOControl)
                || prefix.indexOf('§') >= 0) throw new IllegalArgumentException("Use a prefix of up to 32 characters without formatting codes.");
        java.util.Objects.requireNonNull(color);
    }
    public Component prefixComponent() { return Component.text(prefix.isBlank() ? "" : prefix.strip() + " ", color); }
    public Component displayName(String name) { return prefixComponent().append(Component.text(name, color)); }
    public static NamedTextColor parseColor(String name) {
        NamedTextColor color = NamedTextColor.NAMES.value(name.toLowerCase(java.util.Locale.ROOT));
        if (color == null) throw new IllegalArgumentException("Choose a color from the list.");
        return color;
    }
    public static RoleStyle read(ConfigurationSection config, PlayerRole role) {
        String key = "nametags.roles." + role.id();
        return new RoleStyle(config.getString(key + ".prefix", "[" + role.label() + "]"),
                parseColor(config.getString(key + ".color", role == PlayerRole.DIRECTOR ? "gold" : "gray")));
    }
    public void write(ConfigurationSection config, PlayerRole role) {
        String key = "nametags.roles." + role.id();
        config.set(key + ".prefix", prefix);
        config.set(key + ".color", NamedTextColor.NAMES.key(color));
    }
}
