package me.advait.contender.race;

import me.advait.contender.kit.Kit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class RaceKit {
    public static final String EDITABLE_DEFAULT_ID = "mace_race";
    private static final int RETURN_SLOT = 8;
    private static final NamespacedKey RETURN = new NamespacedKey("contender", "race_return");
    private RaceKit() { }

    /** The saved preset contains only equipment; the race supplies the checkpoint bed. */
    public static Kit defaultKit(String id) {
        Kit kit = new Kit(id);
        kit.setDisplayName("Mace Race");
        kit.setIcon(Material.MACE);
        ItemStack[] contents = new ItemStack[36];
        for (int level = 1; level <= 3; level++) contents[level - 1] =
                tool(Material.MACE, "Wind Burst " + level + " Mace", Enchantment.WIND_BURST, level);
        contents[3] = tool(Material.NETHERITE_SPEAR, "Lunge 3 Spear", Enchantment.LUNGE, 3);
        contents[4] = new ItemStack(Material.WIND_CHARGE, 64);
        contents[5] = new ItemStack(Material.ENDER_PEARL, 16);
        kit.setContents(contents);
        kit.setPvpHurt(true);
        kit.setPveHurt(true);
        return kit;
    }

    public static void give(Player player) { give(player, null); }

    public static void give(Player player, Kit selected) {
        Kit kit = selected == null ? defaultKit(EDITABLE_DEFAULT_ID) : selected;
        validate(kit);
        ItemStack[] contents = prepareContents(kit.getContents());
        ItemStack bed = new ItemStack(Material.RED_BED);
        bed.editMeta(meta -> {
            meta.displayName(Component.text("Return to Checkpoint", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(RETURN, PersistentDataType.BYTE, (byte) 1);
        });
        contents[RETURN_SLOT] = bed;
        kit.apply(player);
        var inventory = player.getInventory();
        inventory.setStorageContents(contents);
        inventory.setHeldItemSlot(0);
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setExhaustion(0);
    }

    /** Validate before teleporting racers or replacing their saved inventories. */
    public static void validate(Kit kit) {
        if (kit == null) return;
        if (kit.isNoClear()) throw new IllegalArgumentException("Choose a saved loadout with No Clear disabled for Mace Race.");
        prepareContents(kit.getContents());
    }

    static ItemStack[] prepareContents(ItemStack[] source) {
        if (source != null && source.length > 36) throw new IllegalArgumentException("Race kits can contain up to 36 inventory slots.");
        ItemStack[] contents = new ItemStack[36];
        if (source != null) for (int i = 0; i < source.length; i++) {
            ItemStack item = source[i];
            if (!empty(item) && !isReturn(item)) contents[i] = item.clone();
        }
        if (!empty(contents[RETURN_SLOT])) {
            int destination = -1;
            // Keep the rest of the hotbar in place when there is room in the main inventory.
            for (int i = 9; i < contents.length; i++) if (empty(contents[i])) { destination = i; break; }
            if (destination < 0) for (int i = 0; i < RETURN_SLOT; i++) if (empty(contents[i])) { destination = i; break; }
            if (destination < 0) throw new IllegalArgumentException("Leave one inventory slot empty for the checkpoint bed.");
            contents[destination] = contents[RETURN_SLOT];
            contents[RETURN_SLOT] = null;
        }
        return contents;
    }

    private static boolean empty(ItemStack item) { return item == null || item.isEmpty() || item.getAmount() <= 0; }

    public static ItemStack tool(Material type, String name, Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(type);
        item.editMeta(meta -> {
            meta.setUnbreakable(true);
            meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.addEnchant(enchantment, level, false);
        });
        return item;
    }
    public static boolean isReturn(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(RETURN, PersistentDataType.BYTE);
    }
    public static boolean isWeapon(ItemStack item) {
        return item != null && (item.getType() == Material.MACE || item.getType().name().endsWith("_SPEAR"));
    }
}
