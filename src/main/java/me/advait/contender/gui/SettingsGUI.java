package me.advait.contender.gui;

import me.advait.contender.PlayerSettingsManager;
import me.advait.contender.PlayerSettingsManager.SpectatorDisguise;
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

    public static final int ALLAY_SLOT = 10;
    public static final int BEE_SLOT = 11;
    public static final int PARROT_SLOT = 12;
    public static final int BAT_SLOT = 13;
    public static final int VEX_SLOT = 14;
    public static final int HAPPY_GHAST_SLOT = 15;

    private SettingsGUI() {}

    public static void open(Player player, PlayerSettingsManager settingsManager) {
        GUIHolder holder = new GUIHolder(GUIType.SETTINGS_GUI);
        holder.setData("settingsManager", settingsManager);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("Settings"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        SpectatorDisguise current = settingsManager.getDisguise(player.getUniqueId());

        inv.setItem(ALLAY_SLOT, disguiseItem(Material.ALLAY_SPAWN_EGG, "Allay", current == SpectatorDisguise.ALLAY));
        inv.setItem(BEE_SLOT, disguiseItem(Material.BEE_SPAWN_EGG, "Bee", current == SpectatorDisguise.BEE));
        inv.setItem(PARROT_SLOT, disguiseItem(Material.PARROT_SPAWN_EGG, "Parrot", current == SpectatorDisguise.PARROT));
        inv.setItem(BAT_SLOT, disguiseItem(Material.BAT_SPAWN_EGG, "Bat", current == SpectatorDisguise.BAT));
        inv.setItem(VEX_SLOT, disguiseItem(Material.VEX_SPAWN_EGG, "Vex", current == SpectatorDisguise.VEX));
        inv.setItem(HAPPY_GHAST_SLOT, disguiseItem(Material.HAPPY_GHAST_SPAWN_EGG, "Ghastling", current == SpectatorDisguise.HAPPY_GHAST));

        // Section label in slot 4 (top center)
        inv.setItem(4, item(Material.ENDER_EYE,
                "<color:" + MessageUtil.MUTED + ">Spectator Disguise",
                "<color:" + MessageUtil.MUTED + ">Choose your disguise when spectating a duel</color>"));

        player.openInventory(inv);
    }

    private static ItemStack disguiseItem(Material material, String name, boolean selected) {
        String color = selected ? "<color:" + MessageUtil.PRIMARY + ">" : "<color:" + MessageUtil.SECONDARY + ">";
        String statusLine = selected
                ? "<color:" + MessageUtil.PRIMARY + ">Selected</color>"
                : "<color:" + MessageUtil.MUTED + ">Click to select</color>";
        return item(material, color + name, statusLine);
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
