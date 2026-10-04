package me.advait.contender.voice;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.role.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Optional Simple Voice Chat integration. By default Contender leaves voice alone. With
 * {@code voice.director-broadcast} on, directors can be heard by everyone, even in other arenas.
 * The Simple Voice Chat classes are only touched when that plugin is installed.
 */
public final class VoiceService extends Module {
    private volatile Set<UUID> broadcasters = Set.of();
    private volatile Set<UUID> online = Set.of();
    private Object bridge;

    public VoiceService(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("voicechat") == null) return;
        try { bridge = VoiceBridge.register(plugin, this); }
        catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING, "Simple Voice Chat is installed but its API could not be reached. Voice works normally without Contender's extras.", failure);
        }
        tasks.repeat(20, 20, this::refresh);
        String warning = spectatorWarning();
        if (warning != null) plugin.getLogger().info(warning);
    }

    @Override protected void onDisable() { broadcasters = Set.of(); }

    public boolean installed() { return bridge != null; }
    public boolean directorBroadcast() { return plugin.getConfig().getBoolean("voice.director-broadcast", false); }

    public void setDirectorBroadcast(boolean enabled) {
        plugin.getConfig().set("voice.director-broadcast", enabled);
        plugin.saveConfig();
        refresh();
    }

    /** Read by the voice thread: an immutable snapshot of who is heard everywhere. */
    Set<UUID> broadcasters() { return broadcasters; }

    /** Read by the voice thread: everyone online, refreshed once a second. */
    Set<UUID> online() { return online; }

    private void refresh() {
        Set<UUID> present = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) present.add(player.getUniqueId());
        online = Set.copyOf(present);
        if (!isEnabled() || !directorBroadcast()) { broadcasters = Set.of(); return; }
        Set<UUID> next = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.DIRECTOR) next.add(player.getUniqueId());
        }
        broadcasters = Set.copyOf(next);
    }

    /** Simple Voice Chat mutes spectator-mode players for everyone else unless spectator_interaction is on. */
    public String spectatorWarning() {
        File file = new File(plugin.getDataFolder().getParentFile(), "voicechat/voicechat-server.properties");
        if (!file.isFile()) return null;
        Properties properties = new Properties();
        try (FileReader reader = new FileReader(file)) { properties.load(reader); }
        catch (IOException ignored) { return null; }
        if (Boolean.parseBoolean(properties.getProperty("spectator_interaction", "false"))) return null;
        return "Simple Voice Chat has spectator_interaction=false, so players in Spectator mode (eliminated players and match spectators) "
                + "can listen but nobody hears them. Set spectator_interaction=true in plugins/voicechat/voicechat-server.properties if they should be heard.";
    }

    public List<String> describe(Player target) {
        List<String> lines = new ArrayList<>();
        lines.add(bridge == null ? "Simple Voice Chat: not installed or not reachable." : "Simple Voice Chat: installed.");
        lines.add("Directors heard everywhere: " + (directorBroadcast() ? "on" : "off") + ".");
        if (bridge != null) lines.add(VoiceBridge.status(bridge, target.getUniqueId()));
        String warning = spectatorWarning();
        if (warning != null) lines.add(warning);
        lines.add("Speak: " + (target.hasPermission("voicechat.speak") ? "allowed" : "blocked") + " · Listen: " + (target.hasPermission("voicechat.listen") ? "allowed" : "blocked"));
        return lines;
    }
}
