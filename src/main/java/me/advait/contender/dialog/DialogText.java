package me.advait.contender.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/** Shared spacing and colors for dialog copy. Values are always literal text. */
public final class DialogText {
    private DialogText() { }

    public static Component muted(String text) { return DialogPalette.text(text, DialogPalette.MUTED); }
    public static Component heading(String text) {
        return DialogPalette.text(text, DialogPalette.ACCENT);
    }
    public static Component detail(String label, String value) {
        return detail(label, value, DialogPalette.TEXT);
    }
    public static Component detail(String label, String value, TextColor color) {
        return muted(label + ": ").append(Component.text(value, color));
    }
    public static Component lines(Component... lines) { return join("\n", lines); }
    public static Component paragraphs(Component... paragraphs) { return join("\n\n", paragraphs); }
    private static Component join(String separator, Component[] parts) {
        Component result = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result = result.append(Component.text(separator));
            result = result.append(parts[i]);
        }
        return result;
    }
    public static Component page(int page, int pages) {
        return DialogPalette.text("Page " + page + " of " + pages, DialogPalette.MUTED);
    }
}
