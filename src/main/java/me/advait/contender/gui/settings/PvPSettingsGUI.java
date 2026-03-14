package me.advait.contender.gui.settings;

import me.advait.contender.gui.GUIHolder;
import me.advait.contender.gui.GUIType;
import me.advait.contender.pvp.PvPSettings;
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

public final class PvPSettingsGUI {

    private static final int SIZE = 27;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int LOBBY_PVP_SLOT = 10;
    public static final int ADMIN_OVERRIDE_SLOT = 13;
    public static final int NON_DUEL_WORLD_PVP_SLOT = 16;
    public static final int BACK_SLOT = 22;

    private PvPSettingsGUI() {}

    public static void open(Player player, PvPSettings pvpSettings) {
        GUIHolder holder = new GUIHolder(GUIType.PVP_SETTINGS_GUI);
        holder.setData("pvpSettings", pvpSettings);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("PvP Settings"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        inv.setItem(4, item(Material.IRON_SWORD,
                "<color:" + MessageUtil.MUTED + ">PvP Settings</color>"));

        inv.setItem(LOBBY_PVP_SLOT, toggleItem(
                "Allow PvP in Lobby",
                pvpSettings.isAllowLobbyPvp(),
                "Players not in any duel can attack each other.",
                "Disabled by default to protect lobby players."
        ));

        inv.setItem(ADMIN_OVERRIDE_SLOT, toggleItem(
                "Admin PvP Override",
                pvpSettings.isAdminPvpOverride(),
                "Admins can attack players regardless of",
                "the PvP settings above."
        ));

        inv.setItem(NON_DUEL_WORLD_PVP_SLOT, toggleItem(
                "Allow PvP in Non-Duel Worlds",
                pvpSettings.isAllowNonDuelWorldPvp(),
                "PvP is allowed in worlds with no active duels.",
                "Disable to fully lock down non-arena worlds."
        ));

        inv.setItem(BACK_SLOT, item(Material.ARROW,
                "<color:" + MessageUtil.SECONDARY + ">Back",
                "<color:" + MessageUtil.MUTED + ">Return to Settings</color>"));

        player.openInventory(inv);
    }

    private static ItemStack toggleItem(String name, boolean value, String... loreLines) {
        Material mat = value ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String statusColor = value ? MessageUtil.PRIMARY : MessageUtil.ERROR;
        String statusText = value ? "Enabled" : "Disabled";

        List<String> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add("<color:" + MessageUtil.MUTED + ">" + line + "</color>");
        }
        lore.add("");
        lore.add("<color:" + statusColor + ">" + statusText + "</color>");
        lore.add("<color:" + MessageUtil.MUTED + ">Click to toggle</color>");

        return item(mat, "<color:" + MessageUtil.ACCENT + ">" + name + "</color>",
                lore.toArray(new String[0]));
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
