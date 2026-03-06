package me.advait.contender.gui;

import me.advait.contender.SpectatorManager;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.util.StringUtil;
import me.advait.contender.vote.VoteSession;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class VoteGUI {

    private static final int SIZE = 54;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private VoteGUI() {}

    public static void open(Player player, VoteSession session, SpectatorManager spectatorManager) {
        GUIHolder holder = new GUIHolder(GUIType.VOTE_GUI);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                MessageUtil.guiTitle("<color:" + MessageUtil.PRIMARY + ">Vote</color>"));
        populate(inv, session, player, spectatorManager);
        player.openInventory(inv);
    }

    public static void populate(Inventory inv, VoteSession session, Player viewer, SpectatorManager spectatorManager) {
        inv.clear();

        ItemStack filler = buildFiller();
        for (int i = 45; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        List<Player> candidates = getCandidates(session, spectatorManager);
        UUID viewerVote = session.getVoteFor(viewer.getUniqueId());
        boolean isAdmin = viewer.hasPermission("contender.master");

        for (int i = 0; i < Math.min(candidates.size(), 45); i++) {
            Player candidate = candidates.get(i);
            int voteCount = session.getVoteCount(candidate.getUniqueId());
            boolean isVotedByViewer = candidate.getUniqueId().equals(viewerVote);
            inv.setItem(i, buildCandidateItem(candidate, voteCount, isVotedByViewer, isAdmin));
        }
    }

    /**
     * Returns the ordered list of candidates shown in the GUI.
     * Must be called consistently between populate() and the click handler.
     */
    public static List<Player> getCandidates(VoteSession session, SpectatorManager spectatorManager) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !spectatorManager.isEventSpectator(p.getUniqueId()) && !session.isRemoved(p.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName))
                .collect(Collectors.toList());
    }

    private static ItemStack buildCandidateItem(Player candidate, int voteCount,
                                                 boolean isVotedByViewer, boolean isAdmin) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(candidate);

        Component head = StringUtil.getPlayerHead(candidate);
        String nameColor = isVotedByViewer ? MessageUtil.PRIMARY : "white";
        Component nameText = MM.deserialize(
                MessageUtil.FONT_OPEN + "<color:" + nameColor + ">" + candidate.getName() + "</color>" +
                MessageUtil.FONT_CLOSE);
        Component displayName = Component.empty()
                .append(head).appendSpace().append(nameText)
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(displayName);

        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<color:" + MessageUtil.SECONDARY + ">" + voteCount + " vote" +
                (voteCount != 1 ? "s" : "") + "</color>").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());

        if (isVotedByViewer) {
            lore.add(MM.deserialize("<color:" + MessageUtil.PRIMARY + ">✓ Your vote</color>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<color:" + MessageUtil.MUTED + ">Left-click to remove your vote</color>")
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(MM.deserialize("<color:" + MessageUtil.ACCENT + ">Left-click to vote</color>")
                    .decoration(TextDecoration.ITALIC, false));
        }

        if (isAdmin) {
            lore.add(MM.deserialize("<color:" + MessageUtil.ERROR + ">Right-click to remove from vote</color>")
                    .decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty().decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
