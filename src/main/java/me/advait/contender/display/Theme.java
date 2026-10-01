package me.advait.contender.display;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;

import java.util.List;

/**
 * A color scheme for display entities, the tab bracket, and celebrations.
 *
 * @param primary   headings, highlighted names, numbers
 * @param secondary values and supporting text
 * @param accent    separators, frames and decorative lines
 * @param muted     captions and inactive text
 * @param panel     display background (RGB; opacity is configured separately)
 * @param fireworks firework and particle colors
 */
public record Theme(String id, String name, TextColor primary, TextColor secondary, TextColor accent, TextColor muted,
                    Color panel, List<Color> fireworks) {

    public Color primaryColor() { return Color.fromRGB(primary.value()); }
    public Color secondaryColor() { return Color.fromRGB(secondary.value()); }
    public Color accentColor() { return Color.fromRGB(accent.value()); }

    /** A display background using this theme's panel color. */
    public Color background(int opacityPercent) {
        int alpha = Math.round(Math.clamp(opacityPercent, 0, 100) * 255f / 100f);
        return Color.fromARGB(alpha, panel.getRed(), panel.getGreen(), panel.getBlue());
    }
}
