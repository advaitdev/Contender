package me.advait.contender.dialog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.ObjectContents;

/** Vanilla atlas sprites, composed as siblings so text keeps its own shadow. */
public enum DialogIcon {
    MACE("items", "item/mace"),
    SAVE("gui", "icon/checkmark"),
    CLOSE("gui", "icon/ping_unknown"),
    PREVIEW("gui", "icon/search"),
    INFO("gui", "icon/info"),
    BACK("items", "item/arrow"),
    NEXT("items", "item/spectral_arrow"),
    REFRESH("items", "item/clock_09"),
    MAP("items", "item/map"),
    SPAWN("items", "item/compass_18"),
    DUEL("items", "item/diamond_sword"),
    AXE("items", "item/diamond_axe"),
    POTION("items", "item/potion"),
    CRYSTAL("items", "item/end_crystal"),
    APPLE("items", "item/golden_apple"),
    SPLASH_POTION("items", "item/splash_potion"),
    TOURNAMENT("items", "item/golden_sword"),
    PLAYERS("items", "item/totem_of_undying"),
    SETTINGS("items", "item/comparator"),
    NAME("items", "item/name_tag"),
    CHAT("items", "item/writable_book"),
    VOICE("items", "item/goat_horn"),
    BOARD("items", "item/paper"),
    PAUSE("items", "item/clock_09"),
    REACH("items", "item/ender_pearl"),
    ARMOR("items", "item/diamond_chestplate"),
    ANCHOR("items", "item/netherite_ingot"),
    BOOTS("items", "item/diamond_boots"),
    JUMP("items", "item/rabbit_foot"),
    STEP("items", "item/feather");

    private final String atlas;
    private final String texture;
    DialogIcon(String atlas, String texture) { this.atlas = atlas; this.texture = texture; }
    public Component sprite() {
        return Component.object(ObjectContents.sprite(Key.key("minecraft", atlas), Key.key("minecraft", texture)))
                .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
    }
    public Component label(String text) { return label(text, DialogPalette.TEXT); }
    public Component label(String text, net.kyori.adventure.text.format.TextColor color) {
        return Component.textOfChildren(sprite(), DialogPalette.text(" " + text, color));
    }
}
