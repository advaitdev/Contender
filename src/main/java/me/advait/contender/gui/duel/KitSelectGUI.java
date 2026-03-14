package me.advait.contender.gui.duel;

import me.advait.contender.duel.DuelSetup;
import me.advait.contender.gui.GUIHolder;
import me.advait.contender.gui.GUIType;
import me.advait.contender.kit.Kit;
import me.advait.contender.kit.KitManager;
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

public final class KitSelectGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int CREATE_KIT_SLOT = 49;
    public static final int BACK_SLOT = 45;

    private KitSelectGUI() {
    }

    public static void open(Player player, KitManager kitManager, DuelSetup setup) {
        GUIHolder holder = new GUIHolder(GUIType.KIT_SELECT);
        holder.setData("setup", setup);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("Select Kit"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        int slot = 0;
        for (Kit kit : kitManager.getKits()) {
            if (slot >= 45) break;

            String blockPlace = kit.isAllowBlockPlace()
                    ? "<color:" + MessageUtil.PRIMARY + ">Yes</color>"
                    : "<color:" + MessageUtil.ERROR + ">No</color>";
            String blockBreak = kit.isAllowBlockBreak()
                    ? "<color:" + MessageUtil.PRIMARY + ">Yes</color>"
                    : "<color:" + MessageUtil.ERROR + ">No</color>";

            inv.setItem(slot, item(kit.getIcon(),
                    "<color:" + MessageUtil.PRIMARY + ">" + kit.getDisplayName(),
                    "<color:" + MessageUtil.MUTED + ">Block Place: " + blockPlace,
                    "<color:" + MessageUtil.MUTED + ">Block Break: " + blockBreak,
                    "",
                    "<color:" + MessageUtil.ACCENT + ">Click to select</color>",
                    "<color:" + MessageUtil.WARNING + ">Shift-click to edit</color>"));
            slot++;
        }

        inv.setItem(CREATE_KIT_SLOT, item(Material.EMERALD,
                "<color:" + MessageUtil.PRIMARY + ">Create New Kit",
                "<color:" + MessageUtil.MUTED + ">Click to create a new kit</color>"));

        inv.setItem(BACK_SLOT, item(Material.ARROW, "<color:" + MessageUtil.ERROR + ">Back"));

        player.openInventory(inv);
    }

    private static ItemStack item(Material material, String name, String... lore) {
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
