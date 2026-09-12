package me.advait.contender.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

public final class MessageUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final String PRIMARY = "#4CAF50";
    public static final String SECONDARY = "#2196F3";
    public static final String ACCENT = "#81C784";
    public static final String ERROR = "#EF5350";
    public static final String WARNING = "#FFA726";
    public static final String MUTED = "#9E9E9E";

    private MessageUtil() {
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public static void sendActionBar(Player player, String miniMessage) {
        player.sendActionBar(MM.deserialize(miniMessage));
    }

    public static void playClick(Player player) {
        player.playSound(Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 1f, 1f));
    }

    public static void playSuccess(Player player) {
        player.playSound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1.2f));
    }

    public static void playCountdownTick(Player player) {
        player.playSound(Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 1f, 1f));
    }

    public static void playCountdownGo(Player player) {
        player.playSound(Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1f, 2f));
    }

    public static void playRoundWin(Player player) {
        player.playSound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 0.8f, 1.5f));
    }

    public static void playElimination(Player player) {
        player.playSound(Sound.sound(Key.key("entity.lightning_bolt.thunder"), Sound.Source.MASTER, 0.5f, 1.2f));
    }

    public static void playDuelEnd(Player player) {
        player.playSound(Sound.sound(Key.key("ui.toast.challenge_complete"), Sound.Source.MASTER, 1f, 1f));
    }

    public static void sendTitle(Player player, String title, String subtitle,
                                 int fadeInTicks, int stayTicks, int fadeOutTicks) {
        player.showTitle(Title.title(
                MM.deserialize(title),
                MM.deserialize(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fadeInTicks * 50L),
                        Duration.ofMillis(stayTicks * 50L),
                        Duration.ofMillis(fadeOutTicks * 50L)
                )
        ));
    }

    public static Component guiTitle(String text) {
        return MM.deserialize(text);
    }
}
