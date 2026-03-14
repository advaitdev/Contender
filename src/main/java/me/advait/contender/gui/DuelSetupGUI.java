package me.advait.contender.gui;

import me.advait.contender.duel.DuelMode;
import me.advait.contender.duel.DuelSetup;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DuelSetupGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int KIT_SLOT = 10;
    public static final int MAP_SLOT = 12;
    public static final int ROUNDS_SLOT = 14;
    public static final int DELAY_SLOT = 16;
    public static final int MODE_SLOT = 22;
    public static final int TEAM1_SLOT = 29;
    public static final int VS_SLOT = 31;
    public static final int TEAM2_SLOT = 33;
    public static final int CANCEL_SLOT = 48;
    public static final int START_SLOT = 50;

    private DuelSetupGUI() {
    }

    public static void open(Player player, DuelSetup setup) {
        GUIHolder holder = new GUIHolder(GUIType.DUEL_SETUP);
        holder.setData("setup", setup);

        Inventory inv = Bukkit.createInventory(holder, SIZE, MessageUtil.guiTitle("Duel Setup"));

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        Kit selectedKit = setup.getSelectedKit();
        String kitName = selectedKit != null ? selectedKit.getDisplayName() : "None";
        Material kitIcon = selectedKit != null ? selectedKit.getIcon() : Material.BARRIER;
        inv.setItem(KIT_SLOT, item(kitIcon,
                "<color:" + MessageUtil.PRIMARY + ">Kit",
                "<color:" + MessageUtil.MUTED + ">Selected: <color:" + MessageUtil.SECONDARY + ">" + kitName + "</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to select</color>"));

        ArenaMap selectedMap = setup.getSelectedMap();
        String mapName = selectedMap != null ? selectedMap.getDisplayName() : "None";
        inv.setItem(MAP_SLOT, item(Material.FILLED_MAP,
                "<color:" + MessageUtil.PRIMARY + ">Map",
                "<color:" + MessageUtil.MUTED + ">Selected: <color:" + MessageUtil.SECONDARY + ">" + mapName + "</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to select</color>"));

        inv.setItem(ROUNDS_SLOT, countItem(Material.CLOCK,
                "<color:" + MessageUtil.PRIMARY + ">Rounds",
                setup.getRounds(),
                "<color:" + MessageUtil.MUTED + ">Best of " + setup.getRounds() + "</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Left-click +1 | Right-click -1</color>"));

        inv.setItem(DELAY_SLOT, countItem(Material.REPEATER,
                "<color:" + MessageUtil.PRIMARY + ">Sort Delay",
                setup.getPreRoundDelay(),
                "<color:" + MessageUtil.MUTED + ">" + setup.getPreRoundDelay() + " seconds</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Left-click +5s | Right-click -5s</color>"));

        boolean isFfa = setup.getMode() == DuelMode.FFA;
        inv.setItem(MODE_SLOT, item(
                isFfa ? Material.TOTEM_OF_UNDYING : Material.SHIELD,
                isFfa ? "<color:" + MessageUtil.WARNING + ">Mode: Free For All" : "<color:" + MessageUtil.PRIMARY + ">Mode: Standard",
                isFfa
                        ? "<color:" + MessageUtil.MUTED + ">All players fight everyone</color>"
                        : "<color:" + MessageUtil.MUTED + ">Team 1 vs Team 2</color>",
                "",
                "<color:" + MessageUtil.ACCENT + ">Click to toggle</color>"));

        MiniMessage mm = MiniMessage.miniMessage();

        List<String> t1Lore = new ArrayList<>();
        t1Lore.add("<color:" + MessageUtil.MUTED + ">Players: " + setup.getTeam1().size() + "</color>");
        for (UUID uuid : setup.getTeam1().getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                String head = mm.serialize(StringUtil.getPlayerHead(p));
                t1Lore.add(head + " <color:" + MessageUtil.SECONDARY + "> - " + p.getName() + "</color>");
            }
        }
        t1Lore.add("");
        t1Lore.add("<color:" + MessageUtil.ACCENT + ">Click to edit</color>");

        inv.setItem(TEAM1_SLOT, item(
                Material.RED_BANNER,
                "<color:" + MessageUtil.ERROR + ">Team 1",
                t1Lore.toArray(new String[0])
        ));

        inv.setItem(VS_SLOT, item(
                Material.IRON_SWORD,
                "<color:" + MessageUtil.WARNING + ">VS"
        ));

        List<String> t2Lore = new ArrayList<>();
        t2Lore.add("<color:" + MessageUtil.MUTED + ">Players: " + setup.getTeam2().size() + "</color>");
        for (UUID uuid : setup.getTeam2().getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                String head = mm.serialize(StringUtil.getPlayerHead(p));
                t2Lore.add(head + " <color:" + MessageUtil.SECONDARY + "> - " + p.getName() + "</color>");
            }
        }
        t2Lore.add("");
        t2Lore.add("<color:" + MessageUtil.ACCENT + ">Click to edit</color>");

        inv.setItem(TEAM2_SLOT, item(
                Material.BLUE_BANNER,
                "<color:" + MessageUtil.SECONDARY + ">Team 2",
                t2Lore.toArray(new String[0])
        ));

        inv.setItem(CANCEL_SLOT, item(
                Material.BARRIER,
                "<color:" + MessageUtil.ERROR + ">Cancel"
        ));

        Material startMat = setup.isValid() ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String startText = setup.isValid()
                ? "<color:" + MessageUtil.PRIMARY + ">Start Duel"
                : "<color:" + MessageUtil.ERROR + ">Setup Incomplete";

        inv.setItem(START_SLOT, item(startMat, startText));

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

    private static ItemStack countItem(Material material, String name, int count, String... lore) {
        ItemStack is = item(material, name, lore);
        is.setAmount(Math.max(1, Math.min(count, 64)));
        return is;
    }
}
