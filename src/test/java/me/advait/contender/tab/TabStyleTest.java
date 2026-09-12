package me.advait.contender.tab;

import me.advait.contender.role.RoleStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TabStyleTest {
    @Test void blankTitleFollowsEachTournamentAndCustomTitleSurvivesSaving() throws Exception {
        var config = new YamlConfiguration();
        var following = TabStyle.read(config);
        assertEquals(Component.text("\nAxe stage\n", NamedTextColor.GOLD), following.header("Axe stage"));
        assertEquals(Component.text("\nSword stage\n", NamedTextColor.GOLD), following.header("Sword stage"));
        var custom = new TabStyle("Best PvPers", NamedTextColor.AQUA, true);
        custom.write(config);
        var loaded = new YamlConfiguration(); loaded.loadFromString(config.saveToString());
        assertEquals(custom, TabStyle.read(loaded));
        assertEquals(Component.text("\nBest PvPers\n", NamedTextColor.AQUA), custom.header("Different stage"));
    }
    @Test void titlePreviewDoesNotSaveAndTreatsFormattingAsLiteralText() {
        var config = new YamlConfiguration();
        var draft = new TabStyle("<red>Finals", NamedTextColor.GOLD, true);
        assertEquals(Component.text("\n<red>Finals\n", NamedTextColor.GOLD), draft.header("Stage"));
        assertTrue(config.getKeys(true).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new TabStyle("Title\nAnother line", NamedTextColor.WHITE, true));
    }
    @Test void rolePreviewIncludesTheSamePrefixSpacingAndNameColor() {
        var style = new RoleStyle("[Camera]", NamedTextColor.AQUA);
        assertEquals(style.prefixComponent().append(Component.text("Advait", style.color())), style.displayName("Advait"));
        assertEquals(Component.text("", NamedTextColor.GRAY).append(Component.text("Advait", NamedTextColor.GRAY)),
                new RoleStyle("", NamedTextColor.GRAY).displayName("Advait"));
    }
    @Test void scoresLineUpForWideNarrowAndClippedNamesAndDifferentScoreLengths() {
        for (String name : new String[]{"iiiiilll", "WWWWWWWWWWWWWWWW", "A long team name that needs clipping"}) {
            for (String score : new String[]{"0", "10", "189"}) {
                assertEquals(132, width(TabText.scored(name, score, NamedTextColor.WHITE), false));
            }
        }
    }
    private int width(Component component, boolean inheritedBold) {
        boolean bold = component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
                || component.decoration(TextDecoration.BOLD) == TextDecoration.State.NOT_SET && inheritedBold;
        int total = 0;
        if (component instanceof TextComponent text) {
            total = text.content().codePoints().map(c -> c == 0x200c ? (bold ? 1 : 0)
                    : TabText.width(new String(Character.toChars(c))) + (bold ? 1 : 0)).sum();
        }
        for (Component child : component.children()) total += width(child, bold);
        return total;
    }
}
