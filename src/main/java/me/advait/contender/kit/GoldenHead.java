package me.advait.contender.kit;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.UUID;

/**
 * UHCR's golden head: a honey bottle drawn with the vanilla player-head model and a golden skin, so it needs no
 * resource pack. Eating it takes a second and gives Regeneration III for 5 seconds and Absorption for 2 minutes,
 * with a 10-second cooldown. Stacks to 64 and leaves no bottle behind.
 */
@SuppressWarnings("UnstableApiUsage")
public final class GoldenHead {
    private static final NamespacedKey TAG = new NamespacedKey("contender", "golden_head");
    private static final NamespacedKey COOLDOWN = new NamespacedKey("contender", "golden_head");
    private static final Key HEAD_MODEL = Key.key("minecraft", "player_head");
    /** Fixed, so every golden head stacks with every other. */
    private static final UUID PROFILE_ID = UUID.fromString("9f1c0d7e-6a4b-4c2e-8d5f-3b7a1e0c4d29");
    private static final String TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODBjMmEzN2MzZDM0OGU4ZWU1NmIxNmM1MGZjN2RlNjcyMDI2OGNiMjUyYzZkNTE1YzliZWZlYTE4OGVmYTk0YyJ9fX0=";

    private GoldenHead() { }

    public static ItemStack create(int amount) {
        ItemStack item = new ItemStack(Material.HONEY_BOTTLE);
        // Meta first: applying meta rewrites the component patch, so setData() comes after.
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Golden Head", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Regeneration III for 5 seconds", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Absorption for 2 minutes", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.ITEM_MODEL, HEAD_MODEL);
        PlayerProfile profile = Bukkit.createProfile(PROFILE_ID);
        profile.setProperty(new ProfileProperty("textures", TEXTURE));
        item.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(profile));
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 64);
        item.unsetData(DataComponentTypes.USE_REMAINDER);
        item.setData(DataComponentTypes.FOOD, FoodProperties.food().canAlwaysEat(true).nutrition(4).saturation(1.2f).build());
        List<PotionEffect> effects = List.of(
                new PotionEffect(PotionEffectType.REGENERATION, 100, 2, true, true, true),
                new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0, true, true, true));
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(1.0f)
                .animation(ItemUseAnimation.EAT)
                .addEffect(ConsumeEffect.applyStatusEffects(effects, 1.0f))
                .build());
        item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(10.0f).cooldownGroup(COOLDOWN).build());
        item.setAmount(Math.clamp(amount, 1, 64));
        return item;
    }

    public static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.HONEY_BOTTLE && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(TAG, PersistentDataType.BYTE);
    }

    /** A fresh golden head in place of a saved one, so a kit always hands out the real thing. */
    public static ItemStack refresh(ItemStack item) {
        return is(item) ? create(item.getAmount()) : item;
    }
}
