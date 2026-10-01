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
    MAP("items", "item/filled_map"),
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
    BOARD("items", "item/painting"),
    PAUSE("items", "item/clock_09"),
    REACH("items", "item/ender_pearl"),
    ARMOR("items", "item/diamond_chestplate"),
    ANCHOR("items", "item/netherite_ingot"),
    BOOTS("items", "item/diamond_boots"),
    JUMP("items", "item/rabbit_foot"),
    STEP("items", "item/feather"),
    REMOVE("items", "item/shears"),
    SLIME("items", "item/slime_ball"),
    HEART("items", "item/glistering_melon_slice"),
    SKULL("blocks", "block/wither_rose"),
    LIGHTNING("blocks", "block/lightning_rod"),
    STAR("items", "item/nether_star"),
    CLOCK("items", "item/clock_00"),
    EYE("items", "item/ender_eye"),
    GLOW("items", "item/glow_ink_sac"),
    WIND("items", "item/wind_charge"),
    BLAZE("items", "item/blaze_powder"),
    SUGAR("items", "item/sugar"),
    PHANTOM("items", "item/phantom_membrane"),
    FIREWORK("items", "item/firework_rocket"),
    BELL("items", "item/bell"),
    SPYGLASS("items", "item/spyglass"),
    BOW("items", "item/bow"),
    TARGET("blocks", "block/target_side"),
    CROWN("items", "item/golden_helmet"),
    RACE("items", "item/elytra"),
    COMPASS("items", "item/compass_00"),
    BRUSH("items", "item/brush"),
    TNT("items", "item/fire_charge"),
    PING("gui", "icon/ping_2"),
    RUSTY("items", "item/stone_sword"),
    BLOOD("items", "item/redstone"),
    INK("items", "item/ink_sac"),
    HUNGER("items", "item/rotten_flesh"),
    EGG("items", "item/egg");

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
