package me.advait.contender.tab;

import me.advait.contender.role.RoleStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;

public record TabStyle(String title, NamedTextColor color, boolean enabled) {
    public TabStyle {
        if (title == null || title.length() > 64 || title.codePoints().anyMatch(Character::isISOControl)
                || title.indexOf('§') >= 0) throw new IllegalArgumentException("Use a title of up to 64 characters without formatting codes.");
        title = title.strip();
        java.util.Objects.requireNonNull(color);
    }
    public Component header(String tournamentName) {
        String heading = title.isBlank() ? tournamentName == null ? "Contender" : tournamentName : title;
        return Component.text("\n" + heading + "\n", color);
    }
    public static TabStyle read(ConfigurationSection config) {
        return new TabStyle(config.getString("tablist.title", ""),
                RoleStyle.parseColor(config.getString("tablist.color", "gold")), config.getBoolean("tablist.enabled", true));
    }
    public void write(ConfigurationSection config) {
        config.set("tablist.title", title);
        config.set("tablist.color", NamedTextColor.NAMES.key(color));
        config.set("tablist.enabled", enabled);
    }
}
