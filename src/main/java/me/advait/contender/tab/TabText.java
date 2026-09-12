package me.advait.contender.tab;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Default-font ASCII advances and one-pixel padding adapted from UHCR's TabFontMetrics. */
public final class TabText {
    private TabText() { }
    public static int width(String text) { return text.codePoints().map(TabText::advance).sum(); }
    private static int advance(int character) {
        return switch (character) {
            case 0x200c, '\n' -> 0;
            case 0x00b7 -> 2;
            case ' ', '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case '`', 'l' -> 3;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            case 0x2026 -> 8;
            default -> 6;
        };
    }
    public static Component padding(int pixels) {
        int size = Math.max(0, pixels);
        return Component.textOfChildren(Component.text(" ".repeat(size / 4)),
                Component.text("\u200c".repeat(size % 4)).decorate(TextDecoration.BOLD));
    }
    public static int width(Component component) { return width(component, false); }
    private static int width(Component component, boolean inheritedBold) {
        boolean bold = switch (component.decoration(TextDecoration.BOLD)) {
            case TRUE -> true; case FALSE -> false; case NOT_SET -> inheritedBold;
        };
        int own = component instanceof net.kyori.adventure.text.TextComponent text
                ? text.content().codePoints().map(c -> advance(c) + (bold && c != ' ' ? 1 : 0)).sum()
                : component instanceof net.kyori.adventure.text.ObjectComponent ? 9 : 0;
        return own + component.children().stream().mapToInt(child -> width(child, bold)).sum();
    }
    public static Component scored(Component name, String score, NamedTextColor scoreColor, int totalWidth) {
        return Component.textOfChildren(name, padding(totalWidth - width(name) - width(score)), Component.text(score, scoreColor))
                .font(Key.key("minecraft", "default"));
    }
    public static Component scored(String name, String score, NamedTextColor color) {
        StringBuilder shown = new StringBuilder();
        int width = 0;
        boolean shorten = width(name) > 108;
        for (int codepoint : name.codePoints().toArray()) {
            if (width + advance(codepoint) > (shorten ? 100 : 108)) break;
            shown.appendCodePoint(codepoint); width += advance(codepoint);
        }
        if (shorten) { shown.append('…'); width += 8; }
        return Component.textOfChildren(Component.text(shown.toString(), color),
                padding(132 - width - width(score)), Component.text(score, NamedTextColor.GOLD))
                .font(Key.key("minecraft", "default"));
    }
}
