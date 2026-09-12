package me.advait.contender.dialog;

import me.advait.contender.kit.Kit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KitIconsTest {
    @Test void kitLabelsUseTheMaterialSavedByTheInventoryEditor() {
        Kit kit = new Kit("axe"); kit.setIcon(Material.IRON_AXE);
        assertEquals(sprite("items", "item/iron_axe"), KitIcons.label(kit).children().getFirst());
        kit.setIcon(Material.END_CRYSTAL);
        assertEquals(sprite("items", "item/end_crystal"), KitIcons.label(kit).children().getFirst());
    }
    @Test void animatedItemsBlocksAndPotionsUseRealVanillaTextures() {
        assertEquals(sprite("items", "item/clock_09"), KitIcons.sprite(Material.CLOCK));
        assertEquals(sprite("blocks", "block/grass_block_side"), KitIcons.sprite(Material.GRASS_BLOCK));
        assertEquals(sprite("items", "item/potion"), KitIcons.sprite(Material.POTION));
        assertEquals(sprite("items", "item/splash_potion"), KitIcons.sprite(Material.SPLASH_POTION));
    }
    @Test void MaterialsWithoutAnInlineSpriteHaveAVisibleFallback() {
        assertEquals(DialogIcon.DUEL.sprite(), KitIcons.sprite(Material.AIR));
        assertEquals(DialogIcon.DUEL.sprite(), KitIcons.sprite(Material.SHIELD));
    }
    private static Component sprite(String atlas, String texture) {
        return Component.object(ObjectContents.sprite(Key.key("minecraft", atlas), Key.key("minecraft", texture)))
                .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }
}
