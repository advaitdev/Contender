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
        String fallbackPrefix = "[" + role.label() + "]";
        NamedTextColor fallbackColor = role == PlayerRole.DIRECTOR ? NamedTextColor.GOLD : NamedTextColor.GRAY;
        String prefix = config.getString(key + ".prefix", fallbackPrefix);
        try { new RoleStyle(prefix, fallbackColor); }
        catch (IllegalArgumentException invalid) { warnOnce(key + ".prefix", prefix); prefix = fallbackPrefix; }
        return new RoleStyle(prefix, lenientColor(config.getString(key + ".color"), fallbackColor, key + ".color"));
    }

    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Reads a color from config, falling back (with one warning) when someone typed a name that doesn't exist. */
    public static NamedTextColor lenientColor(String name, NamedTextColor fallback, String path) {
        if (name == null) return fallback;
        NamedTextColor color = NamedTextColor.NAMES.value(name.toLowerCase(java.util.Locale.ROOT));
        if (color != null) return color;
        warnOnce(path, name);
        return fallback;
    }

    /** Logs an invalid config value the first time it's read, not on every refresh. */
    public static void warnOnce(String path, String value) { warnOnce(path, value, "It isn't valid."); }
    public static void warnOnce(String path, String value, String detail) {
        if (WARNED.add(path + "=" + value)) {
            org.bukkit.Bukkit.getLogger().warning("[Contender] Ignoring " + path + " in config.yml: \"" + value + "\". " + detail + " Using the default.");
        }
    }
    public void write(ConfigurationSection config, PlayerRole role) {
        String key = "nametags.roles." + role.id();
        config.set(key + ".prefix", prefix);
        config.set(key + ".color", NamedTextColor.NAMES.key(color));
    }
}
