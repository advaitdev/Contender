package me.advait.contender.core;

import me.advait.contender.dialog.DialogPalette;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

/** Chat, title and action bar output in the shared palette. */
public final class Msg {
    private Msg() { }

    public static Component text(String text, TextColor color) { return DialogPalette.text(text, color); }

    public static void info(CommandSender to, String text) { to.sendMessage(text(text, DialogPalette.TEXT)); }
    public static void success(CommandSender to, String text) { to.sendMessage(text(text, DialogPalette.SUCCESS)); }
    public static void error(CommandSender to, String text) { to.sendMessage(text(text, DialogPalette.DANGER)); }
    public static void hint(CommandSender to, String text) { to.sendMessage(text(text, DialogPalette.MUTED)); }
    public static void warn(CommandSender to, String text) { to.sendMessage(text(text, DialogPalette.WARNING)); }

    public static void broadcast(Component message) {
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
        Bukkit.getConsoleSender().sendMessage(message);
    }

    public static void send(Collection<UUID> players, Component message) {
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
    }

    public static void actionBar(Collection<UUID> players, Component message) {
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendActionBar(message);
        }
    }

    public static void title(Audience audience, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        audience.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    public static void title(Collection<UUID> players, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) title(player, title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    /** The innermost useful message of a failure, for chat and dialogs. */
    public static String reason(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException || current.getMessage() == null)) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "Something went wrong. Check the server log." : current.getMessage();
    }
}
