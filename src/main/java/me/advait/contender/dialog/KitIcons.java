package me.advait.contender.dialog;

import me.advait.contender.kit.Kit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;

import java.io.IOException;
import java.util.Properties;

/** Kit editor materials resolved to verified vanilla sprites; no resource pack is needed. */
public final class KitIcons {
    private static final Properties SPRITES = load();
    private KitIcons() { }

    private static Properties load() {
        Properties sprites = new Properties();
        try (var input = KitIcons.class.getResourceAsStream("/item-sprites.properties")) {
            if (input == null) throw new IllegalStateException("Missing vanilla item sprite catalog.");
            sprites.load(input);
        } catch (IOException failure) { throw new IllegalStateException("Could not read vanilla item sprites.", failure); }
        return sprites;
    }
    public static Component sprite(Material material) {
        String path = material == null ? null : SPRITES.getProperty(material.getKey().getKey());
        if (path == null) return DialogIcon.DUEL.sprite();
        return Component.object(ObjectContents.sprite(Key.key("minecraft", path.startsWith("block/") ? "blocks" : "items"), Key.key("minecraft", path)))
                .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }
    public static Component label(Kit kit) {
        return Component.textOfChildren(sprite(kit.getIcon()), DialogPalette.text(" " + kit.getDisplayName(), DialogPalette.TEXT));
    }
}
