package me.advait.contender.util;

import me.advait.contender.core.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/** MiniMessage helpers for inventory menus, using the same colors as the dialogs. */
public final class MessageUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final String PRIMARY = "#FCD05C";
    public static final String SECONDARY = "#F0EADF";
    public static final String ACCENT = "#FCD05C";
    public static final String ERROR = "#F2635E";
    public static final String WARNING = "#FFA940";
    public static final String MUTED = "#B3ABA0";

    private MessageUtil() { }

    public static Component parse(String miniMessage) { return MM.deserialize(miniMessage); }
    public static Component guiTitle(String text) { return MM.deserialize(text); }
    public static void playClick(Player player) { Sounds.CLICK.play(player); }
    public static void playSuccess(Player player) { Sounds.SUCCESS.play(player); }
}
