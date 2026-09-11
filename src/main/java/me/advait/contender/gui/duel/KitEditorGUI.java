package me.advait.contender.gui.duel;

import me.advait.contender.gui.GUIHolder;
import me.advait.contender.gui.GUIType;
import me.advait.contender.kit.Kit;
import me.advait.contender.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class KitEditorGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Slots 0-35: main inventory items
    // Slots 36-39: armor (helmet, chest, legs, boots)
    // Slot 40: offhand
    public static final int HELMET_SLOT = 36;
    public static final int CHESTPLATE_SLOT = 37;
    public static final int LEGGINGS_SLOT = 38;
    public static final int BOOTS_SLOT = 39;
    public static final int OFFHAND_SLOT = 40;

    public static final int NO_CLEAR_SLOT = 41;
    public static final int BLOCK_PLACE_SLOT = 42;
    public static final int BLOCK_BREAK_SLOT = 43;
    public static final int NATURAL_REGEN_SLOT = 44;
    public static final int SPECTATOR_INVISIBLE_SLOT = 45;
    public static final int DELETE_SLOT = 46;
    public static final int CANCEL_SLOT = 48;
    public static final int SAVE_SLOT = 50;
    public static final int ICON_SLOT = 52;

    private KitEditorGUI() {
    }

    public static void open(Player player, Kit kit, boolean isNew) {
        GUIHolder holder = new GUIHolder(GUIType.KIT_EDITOR);
        holder.setData("kit", kit);
        holder.setData("isNew", isNew);

        Inventory inv = Bukkit.createInventory(holder, SIZE,
                MessageUtil.guiTitle("Kit Editor: " + kit.getDisplayName()));

        ItemStack filler = controlItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < SIZE; i++) {
            if (i != SPECTATOR_INVISIBLE_SLOT && i != DELETE_SLOT && i != CANCEL_SLOT && i != SAVE_SLOT && i != ICON_SLOT) {
                inv.setItem(i, filler);
            }
        }

        // Armor slot placeholders
        inv.setItem(HELMET_SLOT, getSlotPlaceholder(HELMET_SLOT));
        inv.setItem(CHESTPLATE_SLOT, getSlotPlaceholder(CHESTPLATE_SLOT));
        inv.setItem(LEGGINGS_SLOT, getSlotPlaceholder(LEGGINGS_SLOT));
        inv.setItem(BOOTS_SLOT, getSlotPlaceholder(BOOTS_SLOT));
        inv.setItem(OFFHAND_SLOT, getSlotPlaceholder(OFFHAND_SLOT));

        if (!isNew) {
            ItemStack[] contents = kit.getContents();
            if (contents != null) {
                for (int i = 0; i < Math.min(contents.length, 36); i++) {
                    if (contents[i] != null) inv.setItem(i, contents[i].clone());
                }
            }

            ItemStack[] armor = kit.getArmor();
            if (armor != null) {
                if (armor.length > 3 && armor[3] != null) inv.setItem(HELMET_SLOT, armor[3].clone());
                if (armor.length > 2 && armor[2] != null) inv.setItem(CHESTPLATE_SLOT, armor[2].clone());
                if (armor.length > 1 && armor[1] != null) inv.setItem(LEGGINGS_SLOT, armor[1].clone());
                if (armor.length > 0 && armor[0] != null) inv.setItem(BOOTS_SLOT, armor[0].clone());
            }

            if (kit.getOffhand() != null) inv.setItem(OFFHAND_SLOT, kit.getOffhand().clone());
        }

        boolean nc = kit.isNoClear();
        inv.setItem(NO_CLEAR_SLOT, controlItem(nc ? Material.LIME_DYE : Material.RED_DYE,
                "<color:" + MessageUtil.PRIMARY + ">No Clear",
                "<color:" + MessageUtil.MUTED + ">Currently: " + (nc
                        ? "<color:" + MessageUtil.PRIMARY + ">Enabled</color>"
                        : "<color:" + MessageUtil.ERROR + ">Disabled</color>"),
                "<color:" + MessageUtil.MUTED + ">Each player keeps their own inventory</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to toggle</color>"));

        boolean bp = kit.isAllowBlockPlace();
        inv.setItem(BLOCK_PLACE_SLOT, controlItem(bp ? Material.LIME_DYE : Material.RED_DYE,
                "<color:" + MessageUtil.PRIMARY + ">Block Placement",
                "<color:" + MessageUtil.MUTED + ">Currently: " + (bp
                        ? "<color:" + MessageUtil.PRIMARY + ">Enabled</color>"
                        : "<color:" + MessageUtil.ERROR + ">Disabled</color>"),
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to toggle</color>"));

        boolean bb = kit.isAllowBlockBreak();
        inv.setItem(BLOCK_BREAK_SLOT, controlItem(bb ? Material.LIME_DYE : Material.RED_DYE,
                "<color:" + MessageUtil.PRIMARY + ">Block Breaking",
                "<color:" + MessageUtil.MUTED + ">Currently: " + (bb
                        ? "<color:" + MessageUtil.PRIMARY + ">Enabled</color>"
                        : "<color:" + MessageUtil.ERROR + ">Disabled</color>"),
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to toggle</color>"));

        boolean nr = kit.isNaturalRegen();
        inv.setItem(NATURAL_REGEN_SLOT, controlItem(nr ? Material.LIME_DYE : Material.RED_DYE,
                "<color:" + MessageUtil.PRIMARY + ">Natural Regeneration",
                "<color:" + MessageUtil.MUTED + ">Currently: " + (nr
                        ? "<color:" + MessageUtil.PRIMARY + ">Enabled</color>"
                        : "<color:" + MessageUtil.ERROR + ">Disabled</color>"),
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to toggle</color>"));

        boolean si = kit.isSpectatorInvisible();
        inv.setItem(SPECTATOR_INVISIBLE_SLOT, controlItem(si ? Material.LIME_DYE : Material.RED_DYE,
                "<color:" + MessageUtil.PRIMARY + ">Spectator Invisibility",
                "<color:" + MessageUtil.MUTED + ">Currently: " + (si
                        ? "<color:" + MessageUtil.PRIMARY + ">Enabled</color>"
                        : "<color:" + MessageUtil.ERROR + ">Disabled</color>"),
                "<color:" + MessageUtil.MUTED + ">Enabled: always hidden from contestants</color>",
                "<color:" + MessageUtil.MUTED + ">Disabled: hidden within 20 blocks</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to toggle</color>"));

        if (!isNew) {
            inv.setItem(DELETE_SLOT, controlItem(Material.TNT,
                    "<color:" + MessageUtil.ERROR + ">Delete Kit",
                    "<color:" + MessageUtil.MUTED + ">Shift-click to confirm</color>"));
        } else {
            inv.setItem(DELETE_SLOT, filler);
        }

        inv.setItem(CANCEL_SLOT, controlItem(Material.BARRIER, "<color:" + MessageUtil.ERROR + ">Cancel"));
        inv.setItem(SAVE_SLOT, controlItem(Material.LIME_CONCRETE, "<color:" + MessageUtil.PRIMARY + ">Save Kit"));
        inv.setItem(ICON_SLOT, controlItem(kit.getIcon(),
                "<color:" + MessageUtil.PRIMARY + ">Kit Icon",
                "<color:" + MessageUtil.MUTED + ">Current: " + kit.getIcon().name() + "</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Click with an item to set</color>"));

        player.openInventory(inv);
    }

    public static boolean isItemSlot(int slot) {
        return slot >= 0 && slot <= 40;
    }

    /** Returns the empty-state placeholder for armor/offhand slots. */
    public static ItemStack getSlotPlaceholder(int slot) {
        return switch (slot) {
            case HELMET_SLOT     -> controlItem(Material.ARMOR_STAND, "<color:" + MessageUtil.MUTED + ">Helmet Slot",
                    "<color:" + MessageUtil.ACCENT + ">Left-click to place</color>",
                    "<color:" + MessageUtil.MUTED + ">Right-click to clear</color>");
            case CHESTPLATE_SLOT -> controlItem(Material.ARMOR_STAND, "<color:" + MessageUtil.MUTED + ">Chestplate Slot",
                    "<color:" + MessageUtil.ACCENT + ">Left-click to place</color>",
                    "<color:" + MessageUtil.MUTED + ">Right-click to clear</color>");
            case LEGGINGS_SLOT   -> controlItem(Material.ARMOR_STAND, "<color:" + MessageUtil.MUTED + ">Leggings Slot",
                    "<color:" + MessageUtil.ACCENT + ">Left-click to place</color>",
                    "<color:" + MessageUtil.MUTED + ">Right-click to clear</color>");
            case BOOTS_SLOT      -> controlItem(Material.ARMOR_STAND, "<color:" + MessageUtil.MUTED + ">Boots Slot",
                    "<color:" + MessageUtil.ACCENT + ">Left-click to place</color>",
                    "<color:" + MessageUtil.MUTED + ">Right-click to clear</color>");
            case OFFHAND_SLOT    -> controlItem(Material.RED_STAINED_GLASS_PANE, "<color:" + MessageUtil.MUTED + ">Offhand Slot",
                    "<color:" + MessageUtil.ACCENT + ">Left-click to place</color>",
                    "<color:" + MessageUtil.MUTED + ">Right-click to clear</color>");
            default -> new ItemStack(Material.AIR);
        };
    }

    private static ItemStack controlItem(Material material, String name, String... lore) {
        ItemStack is = new ItemStack(material);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(MM.deserialize(MessageUtil.FONT_OPEN + name + MessageUtil.FONT_CLOSE).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> loreList = new ArrayList<>();
            for (String l : lore) {
                loreList.add(MM.deserialize(l).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreList);
        }
        is.setItemMeta(meta);
        return is;
    }
}
