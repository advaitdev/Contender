package me.advait.contender.gui.settings;

import me.advait.contender.chat.ChatSettings;
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

public final class ChatSettingsGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int SPECTATOR_GAME_CHAT_SLOT = 10;
    public static final int SPECTATOR_MUTE_VC_SLOT = 11;
    public static final int SPECTATOR_DEAFEN_VC_SLOT = 12;
    public static final int CONTESTANT_GAME_CHAT_SLOT = 14;
    public static final int CONTESTANT_MUTE_VC_SLOT = 15;
    public static final int CONTESTANT_DEAFEN_VC_SLOT = 16;
    public static final int ADMIN_OVERRIDE_SLOT = 19;
    public static final int LOBBY_GAME_CHAT_SLOT = 22;
    public static final int LOBBY_MUTE_VC_SLOT = 25;
    public static final int BACK_SLOT = 49;

    private ChatSettingsGUI() {}

    public static void open(Player player, ChatSettings chatSettings) {
        GUIHolder holder = new GUIHolder(GUIType.CHAT_SETTINGS_GUI);
        holder.setData("chatSettings", chatSettings);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("Chat & Voice Settings"));

        // Fill all with gray glass pane
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        // Section header labels (row 0)
        inv.setItem(1, item(Material.IRON_SWORD,
                "<color:" + MessageUtil.MUTED + ">Spectator Rules</color>"));
        inv.setItem(5, item(Material.DIAMOND_SWORD,
                "<color:" + MessageUtil.MUTED + ">Contestant Rules</color>"));

        // Spectator toggles
        inv.setItem(SPECTATOR_GAME_CHAT_SLOT, toggleItem(
                "Allow Spectator Game Chat",
                "Spectators can send messages in chat.",
                chatSettings.isAllowSpectatorGameChat()
        ));
        inv.setItem(SPECTATOR_MUTE_VC_SLOT, toggleItem(
                "Mute Spectator Voice Chat",
                "Spectators cannot speak in voice chat.",
                chatSettings.isMuteSpectatorVoiceChat()
        ));
        inv.setItem(SPECTATOR_DEAFEN_VC_SLOT, toggleItem(
                "Deafen Spectator Voice Chat",
                "Spectators are isolated and cannot hear",
                "contestants or other spectators in voice chat.",
                chatSettings.isDeafenSpectatorVoiceChat()
        ));

        // Contestant toggles
        inv.setItem(CONTESTANT_GAME_CHAT_SLOT, toggleItem(
                "Allow Contestant Game Chat",
                "Contestants can send messages in chat.",
                chatSettings.isAllowContestantGameChat()
        ));
        inv.setItem(CONTESTANT_MUTE_VC_SLOT, toggleItem(
                "Mute Contestant Voice Chat",
                "Contestants cannot speak in voice chat.",
                chatSettings.isMuteContestantVoiceChat()
        ));
        inv.setItem(CONTESTANT_DEAFEN_VC_SLOT, toggleItem(
                "Deafen Contestant Voice Chat",
                "Contestants cannot hear voice chat.",
                chatSettings.isDeafenContestantVoiceChat()
        ));

        // Admin override
        inv.setItem(ADMIN_OVERRIDE_SLOT, toggleItem(
                "Admins Override All",
                "Players with contender.admin permission",
                "bypass all chat and voice restrictions.",
                chatSettings.isAdminsOverrideAll()
        ));

        // Lobby toggles
        inv.setItem(LOBBY_GAME_CHAT_SLOT, toggleItem(
                "Allow Lobby Game Chat",
                "Lobby players can send messages in chat.",
                chatSettings.isAllowLobbyGameChat()
        ));
        inv.setItem(LOBBY_MUTE_VC_SLOT, toggleItem(
                "Mute Lobby Voice Chat",
                "Lobby players cannot speak in voice chat.",
                chatSettings.isMuteLobbyVoiceChat()
        ));

        // Back button
        inv.setItem(BACK_SLOT, item(Material.ARROW,
                "<color:" + MessageUtil.SECONDARY + ">Back",
                "<color:" + MessageUtil.MUTED + ">Return to Settings</color>"));

        player.openInventory(inv);
    }

    private static ItemStack toggleItem(String name, String loreLine, boolean value) {
        return toggleItem(name, new String[]{loreLine}, value);
    }

    private static ItemStack toggleItem(String name, String loreLine1, String loreLine2, boolean value) {
        return toggleItem(name, new String[]{loreLine1, loreLine2}, value);
    }

    private static ItemStack toggleItem(String name, String loreLine1, String loreLine2, String loreLine3, boolean value) {
        return toggleItem(name, new String[]{loreLine1, loreLine2, loreLine3}, value);
    }

    private static ItemStack toggleItem(String name, String[] loreLines, boolean value) {
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

        return item(mat, "<color:" + MessageUtil.ACCENT + ">" + name + "</color>", lore.toArray(new String[0]));
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
