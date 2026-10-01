package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.Locale;

/** A large countdown in the lobby. The position is saved; the display only exists while a vote runs. */
final class VoteTimerDisplay {
    private final Contender plugin;
    private TextDisplay display;
    private Chunk ticket;

    VoteTimerDisplay(Contender plugin) { this.plugin = plugin; }

    void place(Player player) {
        if (plugin.getArenas().isArenaWorld(player.getWorld())) throw new IllegalArgumentException("Place the timer outside the arena world.");
        hide();
        Location location = player.getEyeLocation().add(player.getLocation().getDirection().multiply(4)).add(0, 1, 0);
        var config = plugin.getConfig();
        config.set("vote-timer.world", location.getWorld().getName());
        config.set("vote-timer.x", location.getX());
        config.set("vote-timer.y", location.getY());
        config.set("vote-timer.z", location.getZ());
        plugin.saveConfig();
    }

    void remove() {
        hide();
        plugin.getConfig().set("vote-timer", null);
        plugin.saveConfig();
    }

    boolean placed() { return plugin.getConfig().getString("vote-timer.world") != null; }

    void update(int seconds) {
        if (seconds < 0) { hide(); return; }
        Theme theme = plugin.getThemes().current();
        Component text = Component.text("Vote", theme.primary()).appendNewline()
                .append(Component.text(String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60),
                        seconds <= 10 ? me.advait.contender.dialog.DialogPalette.DANGER : theme.secondary()));
        if (display == null || !display.isValid()) {
            String name = plugin.getConfig().getString("vote-timer.world");
            World world = name == null ? null : Bukkit.getWorld(name);
            if (world == null) return;
            var config = plugin.getConfig();
            Location location = new Location(world, config.getDouble("vote-timer.x"), config.getDouble("vote-timer.y"), config.getDouble("vote-timer.z"));
            if (location.getChunk().addPluginChunkTicket(plugin)) ticket = location.getChunk();
            display = Holograms.text(location, text, 0.01f, Display.Billboard.CENTER, theme.background(0), "vote_timer");
            TextDisplay created = display;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(created, Holograms.scaled(2.6f), 8), 2L);
        } else {
            display.text(text);
        }
    }

    void hide() {
        Holograms.remove(display);
        display = null;
        if (ticket != null) { ticket.removePluginChunkTicket(plugin); ticket = null; }
    }
}
