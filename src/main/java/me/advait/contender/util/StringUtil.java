package me.advait.contender.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
//import net.momirealms.customnameplates.api.CustomNameplatesAPI;
//import net.momirealms.customnameplates.api.feature.background.Background;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public final class StringUtil {

    public static final String NOXESIUM_NOT_INSTALLED = "You must install Noxesium to play on this server!";

//    @Deprecated
//    /**
//     * This currently has an issue with the background overflowing; ignore it for now.
//     */
//    public static Component generateVSText(UUID uuid1, UUID uuid2) {
//        // Offline in case they disconnect, or just for debug purposes
//        // There's no real reason for them to be online players
//        OfflinePlayer player1 = Bukkit.getOfflinePlayer(uuid1);
//        OfflinePlayer player2 = Bukkit.getOfflinePlayer(uuid2);
//
//        Optional<Background> background = CustomNameplatesAPI.getInstance().getBackground("bedrock_1");
//
//        Component player1Head = Component.translatable("%nox_uuid%" + uuid1 + ",false,0,4,1.0,true", NOXESIUM_NOT_INSTALLED);
//        Component player2Head = Component.translatable("%nox_uuid%" + uuid2 + ",false,0,4,1.0,true", NOXESIUM_NOT_INSTALLED);
//
//        String player1HeadSerialized = MiniMessage.miniMessage().serialize(player1Head);
//        String player2HeadSerialized = MiniMessage.miniMessage().serialize(player2Head);
//
//        String mainText = " <font:minecraft:ranyth>" + player1.getName() + "</font> vs ";
//        String latterText = " <font:minecraft:ranyth>" + player2.getName() + "</font>";
//
//        String finalText = CustomNameplatesAPI.getInstance().createTextWithImage(
//                player1HeadSerialized + mainText + player2HeadSerialized + latterText,
//                background.get(),
//                1,
//                1);
//
//        return MiniMessage.miniMessage().deserialize(finalText);
//    }
//
//    @Deprecated
//    public static Component generateVSText(Player player1, Player player2) {
//        UUID uuid1 = player1.getUniqueId();
//        UUID uuid2 = player2.getUniqueId();
//        return generateVSText(uuid1, uuid2);
//    }
//
//    @Deprecated
//    public static Component generateVSText(String player1Name, String player2Name) {
//        UUID uuid1 = Bukkit.getOfflinePlayer(player1Name).getUniqueId();
//        UUID uuid2 = Bukkit.getOfflinePlayer(player2Name).getUniqueId();
//        return generateVSText(uuid1, uuid2);
//    }

    public static Component getPlayerHead(UUID uuid) {
        return Component.translatable("%nox_uuid%" + uuid + ",false,0,0,1.0,true", "").color(NamedTextColor.WHITE);
    }

    public static Component getPlayerHead(Player player) {
        return getPlayerHead(player.getUniqueId());
    }

}
