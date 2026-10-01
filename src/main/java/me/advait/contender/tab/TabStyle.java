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
        return Component.text("\n" + heading(tournamentName) + "\n", color);
    }
    /** The title text alone: the custom title, otherwise the event name. */
    public String heading(String tournamentName) {
        return title.isBlank() ? tournamentName == null ? "Contender" : tournamentName : title;
    }
    public static TabStyle read(ConfigurationSection config) {
        var color = RoleStyle.lenientColor(config.getString("tablist.color"), net.kyori.adventure.text.format.NamedTextColor.GOLD, "tablist.color");
        boolean enabled = config.getBoolean("tablist.enabled", true);
        String title = config.getString("tablist.title", "");
        try { return new TabStyle(title, color, enabled); }
        catch (IllegalArgumentException invalid) {
            // Read every second by the tab list and the board, so warn once per bad value.
            me.advait.contender.role.RoleStyle.warnOnce("tablist.title", title, invalid.getMessage());
            return new TabStyle("", color, enabled);
        }
    }
    public void write(ConfigurationSection config) {
        config.set("tablist.title", title);
        config.set("tablist.color", NamedTextColor.NAMES.key(color));
        config.set("tablist.enabled", enabled);
    }
}
