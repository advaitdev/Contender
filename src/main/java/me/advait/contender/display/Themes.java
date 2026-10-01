package me.advait.contender.display;

import me.advait.contender.Contender;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The built-in color schemes and the one currently selected in config.yml. */
public final class Themes {
    private static final Map<String, Theme> ALL = new LinkedHashMap<>();

    static {
        add("ocean", "Ocean", 0x5BC8F5, 0xFFFFFF, 0x2F8FD8, 0x9FB4C7, 0x0E1A26, 0x5BC8F5, 0xFFFFFF, 0x2F8FD8);
        add("amethyst", "Amethyst", 0xC58BFF, 0xFF9AD5, 0x9B5DE5, 0xB9A7C9, 0x1A1024, 0xC58BFF, 0xFF9AD5, 0x9B5DE5);
        add("sunset", "Sunset", 0xFF9E5E, 0xFFD6A5, 0xF2665A, 0xC9AFA0, 0x24140F, 0xFF9E5E, 0xF2665A, 0xFFD6A5);
        add("gold", "Gold", 0xFCD05C, 0xF0EADF, 0xD9A520, 0xB3ABA0, 0x1F1A10, 0xFCD05C, 0xFFFFFF, 0xD9A520);
        add("mint", "Mint", 0x5EF0B5, 0xE6FFF5, 0x26B98A, 0x9CC9B8, 0x0E1F1A, 0x5EF0B5, 0xE6FFF5, 0x26B98A);
        add("crimson", "Crimson", 0xFF5A5F, 0xFFD1D3, 0xC81D25, 0xC9A3A5, 0x220E10, 0xFF5A5F, 0xFFD1D3, 0xC81D25);
        add("frost", "Frost", 0xA8E6FF, 0xF2FBFF, 0x7FB7D6, 0xA7B8C2, 0x101820, 0xA8E6FF, 0xF2FBFF, 0x7FB7D6);
        add("mono", "Mono", 0xFFFFFF, 0xC8C8C8, 0x8A8A8A, 0x9E9E9E, 0x121212, 0xFFFFFF, 0xC8C8C8, 0x8A8A8A);
    }

    private static void add(String id, String name, int primary, int secondary, int accent, int muted, int panel, int... fireworks) {
        List<Color> colors = new ArrayList<>();
        for (int rgb : fireworks) colors.add(Color.fromRGB(rgb));
        ALL.put(id, new Theme(id, name, TextColor.color(primary), TextColor.color(secondary), TextColor.color(accent),
                TextColor.color(muted), Color.fromRGB(panel), List.copyOf(colors)));
    }

    private final Contender plugin;
    private final List<Consumer<Theme>> listeners = new ArrayList<>();

    public Themes(Contender plugin) { this.plugin = plugin; }

    public static List<Theme> all() { return List.copyOf(ALL.values()); }

    public static Theme byId(String id) { return ALL.get(id); }

    public Theme current() {
        Theme theme = ALL.get(plugin.getConfig().getString("display.theme", "ocean"));
        return theme == null ? ALL.get("ocean") : theme;
    }

    public void select(String id) {
        Theme theme = ALL.get(id);
        if (theme == null) throw new IllegalArgumentException("Unknown color scheme.");
        plugin.getConfig().set("display.theme", id);
        plugin.saveConfig();
        listeners.forEach(listener -> listener.accept(theme));
    }

    /** Displays that are already in the world redraw themselves when the scheme changes. */
    public void onChange(Consumer<Theme> listener) { listeners.add(listener); }

    public int opacity() { return Math.clamp(plugin.getConfig().getInt("tournament-board.background-opacity", 0), 0, 100); }
}
