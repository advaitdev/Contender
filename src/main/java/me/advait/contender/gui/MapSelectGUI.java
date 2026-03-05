package me.advait.contender.gui;

import me.advait.contender.duel.DuelSetup;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
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

public final class MapSelectGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int BACK_SLOT = 45;

    private MapSelectGUI() {
    }

    public static void open(Player player, MapManager mapManager, DuelSetup setup) {
        GUIHolder holder = new GUIHolder(GUIType.MAP_SELECT);
        holder.setData("setup", setup);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("Select Map"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        int slot = 0;
        for (ArenaMap map : mapManager.getMaps()) {
            if (slot >= 45) break;
            inv.setItem(slot, item(Material.FILLED_MAP,
                    "<color:" + MessageUtil.PRIMARY + ">" + map.getDisplayName(),
                    "<color:" + MessageUtil.MUTED + ">World: <color:" + MessageUtil.SECONDARY + ">" + map.getWorldName() + "</color>",
                    "",
                    "<color:" + MessageUtil.ACCENT + ">Click to select</color>"));
            slot++;
        }

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
