package me.advait.contender.vote;

import me.advait.contender.Contender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

/** The anchor persists; the visible timer exists only for an active vote. */
public final class VoteTimerDisplay {
    private final Contender plugin;
    private TextDisplay display;
    private Chunk ticket;
    public VoteTimerDisplay(Contender plugin) { this.plugin = plugin; }
    public void place(Player player) {
        if (plugin.getArenaManager().isArenaWorld(player.getWorld())) throw new IllegalArgumentException("Place the vote timer outside the arena pool.");
        hide();
        Location location = player.getEyeLocation().add(player.getLocation().getDirection().multiply(4)).add(0, 1, 0);
        plugin.getConfig().set("vote-timer.world", location.getWorld().getName());
        plugin.getConfig().set("vote-timer.x", location.getX()); plugin.getConfig().set("vote-timer.y", location.getY()); plugin.getConfig().set("vote-timer.z", location.getZ());
        plugin.saveConfig();
        var session = plugin.getVoteManager().getActiveSession(); if (session != null) update(session.getRemainingSeconds());
    }
    public void remove() { hide(); plugin.getConfig().set("vote-timer", null); plugin.saveConfig(); }
    public void update(int seconds) {
        if (seconds <= 0) { hide(); return; }
        if (display == null || !display.isValid()) {
            String name = plugin.getConfig().getString("vote-timer.world"); if (name == null) return;
            World world = Bukkit.getWorld(name); if (world == null) return;
            Location location = new Location(world, plugin.getConfig().getDouble("vote-timer.x"), plugin.getConfig().getDouble("vote-timer.y"), plugin.getConfig().getDouble("vote-timer.z"));
            if (location.getChunk().addPluginChunkTicket(plugin)) ticket = location.getChunk();
            display = world.spawn(location, TextDisplay.class, entity -> {
                entity.setPersistent(false); entity.setBillboard(Display.Billboard.CENTER); entity.setShadowed(true);
                entity.setDefaultBackground(false); entity.setBackgroundColor(Color.fromARGB(0)); entity.setLineWidth(300);
                entity.setTransformationMatrix(new Matrix4f().scaling(2.5f));
            });
        }
        display.text(Component.text("Vote", NamedTextColor.GOLD).appendNewline()
                .append(Component.text(String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), seconds <= 10 ? NamedTextColor.RED : NamedTextColor.WHITE)));
    }
    public void hide() {
        if (display != null) { display.remove(); display = null; }
        if (ticket != null) { ticket.removePluginChunkTicket(plugin); ticket = null; }
    }
}
