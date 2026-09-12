package me.advait.contender.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Warm neutrals and gold adapted from UHCR's ColorScheme. Color describes intent, not the icon. */
public final class DialogPalette {
    public static final TextColor ACCENT = TextColor.color(0xFCD05C);
    public static final TextColor TEXT = TextColor.color(0xF0EADF);
    public static final TextColor MUTED = TextColor.color(0xB3ABA0);
    public static final TextColor SUCCESS = TextColor.color(0x63D98A);
    public static final TextColor WARNING = TextColor.color(0xFFA940);
    public static final TextColor DANGER = TextColor.color(0xF2635E);
    private DialogPalette() { }

    public static Component text(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
    }
    /** Also handles components supplied by previews or other plugins. */
    public static Component regular(Component component) {
        return component.decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
                .children(component.children().stream().map(DialogPalette::regular).toList());
    }
}
