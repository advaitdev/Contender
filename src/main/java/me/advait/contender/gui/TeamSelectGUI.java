package me.advait.contender.gui;

import me.advait.contender.duel.DuelSetup;
import me.advait.contender.duel.DuelTeam;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public final class TeamSelectGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int BACK_SLOT = 45;

    private TeamSelectGUI() {
    }

    public static void open(Player player, DuelSetup setup, int teamNumber) {
        GUIHolder holder = new GUIHolder(GUIType.TEAM_SELECT);
        holder.setData("setup", setup);
        holder.setData("teamNumber", teamNumber);

        DuelTeam currentTeam = teamNumber == 1 ? setup.getTeam1() : setup.getTeam2();
        DuelTeam otherTeam = teamNumber == 1 ? setup.getTeam2() : setup.getTeam1();
        String teamColor = teamNumber == 1 ? MessageUtil.ERROR : MessageUtil.SECONDARY;

        Inventory inv = Bukkit.createInventory(holder, SIZE,
                MessageUtil.guiTitle("Team " + teamNumber + " Players"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;

            boolean onThisTeam = currentTeam.hasPlayer(online.getUniqueId());
            boolean onOtherTeam = otherTeam.hasPlayer(online.getUniqueId());

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            skullMeta.setOwningPlayer(online);

            Component playerHead = StringUtil.getPlayerHead(online);
            String statusLine;
            if (onThisTeam) {
                statusLine = "<color:" + MessageUtil.PRIMARY + ">On this team</color>";
                skullMeta.displayName(Component.empty().append(playerHead).appendSpace()
                        .append(MM.deserialize(MessageUtil.FONT_OPEN +
                                "<color:" + teamColor + ">" + online.getName() + "</color>" +
                                MessageUtil.FONT_CLOSE))
                        .decoration(TextDecoration.ITALIC, false));
            } else if (onOtherTeam) {
                statusLine = "<color:" + MessageUtil.ERROR + ">On other team</color>";
                skullMeta.displayName(Component.empty().append(playerHead).appendSpace()
                        .append(MM.deserialize(MessageUtil.FONT_OPEN +
                                "<color:" + MessageUtil.MUTED + "><strikethrough>" +
                                online.getName() + "</strikethrough></color>" +
                                MessageUtil.FONT_CLOSE))
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                statusLine = "<color:" + MessageUtil.MUTED + ">Available</color>";
                skullMeta.displayName(Component.empty().append(playerHead).appendSpace()
                        .append(MM.deserialize(MessageUtil.FONT_OPEN +
                                "<color:" + MessageUtil.SECONDARY + ">" + online.getName() + "</color>" +
                                MessageUtil.FONT_CLOSE))
                        .decoration(TextDecoration.ITALIC, false));
            }

            List<Component> lore = new ArrayList<>();
            lore.add(MM.deserialize(statusLine).decoration(TextDecoration.ITALIC, false));
            if (!onOtherTeam) {
                lore.add(Component.empty());
                String action = onThisTeam ? "remove" : "add";
                lore.add(MM.deserialize("<color:" + MessageUtil.ACCENT + ">Click to " + action + "</color>")
                        .decoration(TextDecoration.ITALIC, false));
            }
            skullMeta.lore(lore);

            if (onThisTeam) {
                skullMeta.setEnchantmentGlintOverride(true);
            }

            head.setItemMeta(skullMeta);
            inv.setItem(slot, head);
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
