package me.advait.contender.gui.settings;

import me.advait.contender.gui.GUIHolder;
import me.advait.contender.gui.GUIType;
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

public final class SettingsGUI {

    private static final int SIZE = 27;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int DISGUISE_SLOT = 10;
    public static final int PVP_SETTINGS_SLOT = 13;
    public static final int CHAT_SETTINGS_SLOT = 16;

    private SettingsGUI() {}

    public static void open(Player player) {
        GUIHolder holder = new GUIHolder(GUIType.SETTINGS_GUI);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("Settings"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        // Section label in slot 4 (top center)
        inv.setItem(4, item(Material.ENDER_EYE,
                "<color:" + MessageUtil.MUTED + ">Settings</color>"));

        // Spectator Disguise sub-menu
        inv.setItem(DISGUISE_SLOT, item(Material.ALLAY_SPAWN_EGG,
                "<color:" + MessageUtil.PRIMARY + ">Spectator Disguise</color>",
                "<color:" + MessageUtil.MUTED + ">Click to configure your disguise</color>",
                "<color:" + MessageUtil.MUTED + ">when spectating a duel</color>"));

        // PvP Settings sub-menu
        inv.setItem(PVP_SETTINGS_SLOT, item(Material.IRON_SWORD,
                "<color:" + MessageUtil.SECONDARY + ">PvP Settings</color>",
                "<color:" + MessageUtil.MUTED + ">Configure PvP rules outside of duels</color>",
                "<color:" + MessageUtil.WARNING + ">Requires admin permission</color>"));

        // Chat & Voice Settings sub-menu
        inv.setItem(CHAT_SETTINGS_SLOT, item(Material.GOAT_HORN,
                "<color:" + MessageUtil.SECONDARY + ">Chat & Voice Settings</color>",
                "<color:" + MessageUtil.MUTED + ">Manage chat and voice restrictions</color>",
                "<color:" + MessageUtil.WARNING + ">Requires admin permission</color>"));

        player.openInventory(inv);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack is = new ItemStack(material);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(MM.deserialize(MessageUtil.FONT_OPEN + name + MessageUtil.FONT_CLOSE)
                .decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> loreList = new ArrayList<>();
            for (String l : lore) {
                loreList.add(MM.deserialize(MessageUtil.FONT_OPEN + l + MessageUtil.FONT_CLOSE)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreList);
        }
        is.setItemMeta(meta);
        return is;
    }
}
