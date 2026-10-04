package me.advait.contender.interview;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.util.YamlStorage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.util.*;

/**
 * Interviews: the interviewee and the director are moved into the interview room, and /uninterview sends
 * both back to exactly where they were. The return points are saved, so a restart or disconnect still
 * puts everyone back.
 */
public final class InterviewService extends Module implements Activity {
    private record Return(Location location, GameMode mode) { }

    private final File file;
    private final Map<UUID, Return> returns = new LinkedHashMap<>();
    private UUID interviewee;
    private UUID interviewer;

    public InterviewService(Contender plugin) {
        super(plugin);
        file = new File(plugin.getDataFolder(), "interview.yml");
    }

    @Override protected void onEnable() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection saved = yaml.getConfigurationSection("returns");
        if (saved != null) for (String key : saved.getKeys(false)) {
            try {
                Location location = saved.getLocation(key + ".location");
                GameMode mode = GameMode.valueOf(saved.getString(key + ".mode", "SURVIVAL"));
                if (location != null) returns.put(UUID.fromString(key), new Return(location, mode));
            } catch (IllegalArgumentException ignored) { }
        }
        // After a restart the interview itself is over; everyone pending is sent back when they are online.
        tasks.later(20, () -> { for (Player player : Bukkit.getOnlinePlayers()) sendBack(player); });
    }

    @Override protected void onDisable() { save(); }

    @Override public String displayName() { return "an interview"; }

    public boolean active() { return interviewee != null; }
    public UUID interviewee() { return interviewee; }

    // ---- Positions -----------------------------------------------------------------------------

    public void setPosition(String role, Location location) {
        if (plugin.getArenas().isArenaWorld(location.getWorld())) throw new IllegalArgumentException("Set interview positions outside the arena world.");
        // Plain coordinates, so the spot still loads when its world isn't loaded yet at startup.
        var config = plugin.getConfig();
        String path = "interview." + role;
        config.set(path, null);
        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", location.getX());
        config.set(path + ".y", location.getY());
        config.set(path + ".z", location.getZ());
        config.set(path + ".yaw", (double) location.getYaw());
        config.set(path + ".pitch", (double) location.getPitch());
        plugin.saveConfig();
    }

    /** The saved spot, or null when it isn't set or its world isn't loaded. */
    public Location position(String role) {
        var section = plugin.getConfig().getConfigurationSection("interview." + role);
        if (section == null) return null;
        org.bukkit.World world = plugin.getServer().getWorld(section.getString("world", ""));
        if (world == null) return null;
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    // ---- Interviews ----------------------------------------------------------------------------

    public void start(Player target, Player director) {
        if (active()) throw new IllegalStateException("Finish the current interview with /uninterview first.");
        Location seat = position("interviewee");
        if (seat == null) throw new IllegalStateException("Set the interviewee's spot first with /setintervieweeposition.");
        Location host = position("interviewer");
        if (director != null && host == null) throw new IllegalStateException("Set your spot first with /setinterviewerposition.");
        if (target.equals(director)) throw new IllegalArgumentException("You can't interview yourself.");
        ActivityRegistry registry = plugin.getRegistry();
        for (Player player : participants(target, director)) {
            if (registry.isPlaying(player.getUniqueId())) {
                throw new IllegalStateException(player.getName() + " is busy in " + registry.owner(player.getUniqueId()).displayName() + ".");
            }
            if (player.isDead()) throw new IllegalStateException(player.getName() + " must respawn first.");
        }
        for (Player player : participants(target, director)) {
            if (registry.isWatching(player.getUniqueId())) plugin.getSpectate().stop(player.getUniqueId(), false);
        }
        List<Player> moved = new ArrayList<>();
        try {
            claimAndMove(target, seat);
            moved.add(target);
            if (director != null) { claimAndMove(director, host); moved.add(director); }
        } catch (RuntimeException failure) {
            for (Player player : moved) sendBack(player);
            for (Player player : participants(target, director)) registry.release(player.getUniqueId(), this);
            throw failure;
        }
        interviewee = target.getUniqueId();
        interviewer = director == null ? null : director.getUniqueId();
        save();
        Msg.title(target, Msg.text("Interview", plugin.getThemes().current().primary()), Msg.text("You've been called in", DialogPalette.MUTED), 6, 50, 10);
        Sounds.ANNOUNCE.play(target);
        if (director != null) Sounds.ANNOUNCE.play(director);
    }

    private static List<Player> participants(Player target, Player director) {
        return director == null ? List.of(target) : List.of(target, director);
    }

    private void claimAndMove(Player player, Location destination) {
        plugin.getRegistry().claim(player.getUniqueId(), this, ActivityRegistry.Involvement.PLAYING);
        returns.putIfAbsent(player.getUniqueId(), new Return(player.getLocation(), player.getGameMode()));
        save();
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (!player.teleport(destination)) throw new IllegalStateException("Could not move " + player.getName() + " into the interview room.");
        if (player.getGameMode() == GameMode.SPECTATOR && plugin.getRoleManager().getRole(player.getUniqueId()) != me.advait.contender.role.PlayerRole.SPECTATOR) {
            player.setGameMode(GameMode.ADVENTURE);
        }
        player.setFallDistance(0);
    }

    /** Ends the interview and returns both players. */
    public void end() {
        if (!active() && returns.isEmpty()) throw new IllegalStateException("Nobody is being interviewed.");
        UUID guest = interviewee, host = interviewer;
        interviewee = null;
        interviewer = null;
        if (guest != null) plugin.getRegistry().release(guest, this);
        if (host != null) plugin.getRegistry().release(host, this);
        for (Player player : Bukkit.getOnlinePlayers()) sendBack(player);
        save();
    }

    private void sendBack(Player player) {
        Return saved = returns.get(player.getUniqueId());
        if (saved == null || player.isDead()) return;
        if (Objects.equals(player.getUniqueId(), interviewee) || Objects.equals(player.getUniqueId(), interviewer)) return;
        Location destination = saved.location();
        World world = destination.getWorld();
        if (world == null || plugin.getArenas().isArenaWorld(world)) destination = plugin.getLobby().location();
        if (destination != null && player.teleport(destination)) {
            player.setGameMode(saved.mode() == GameMode.SPECTATOR && plugin.getRoleManager().getRole(player.getUniqueId()) != me.advait.contender.role.PlayerRole.SPECTATOR
                    ? GameMode.SURVIVAL : saved.mode());
            plugin.getLobby().applyRoleMode(player);
            returns.remove(player.getUniqueId());
            save();
        }
    }

    @Override public void handleQuit(Player player) {
        // The interview ends when either side leaves; the one who stayed goes back now, the other on their next join.
        UUID id = player.getUniqueId();
        if (!id.equals(interviewee) && !id.equals(interviewer)) return;
        UUID other = id.equals(interviewee) ? interviewer : interviewee;
        plugin.getRegistry().release(id, this);
        if (other != null) plugin.getRegistry().release(other, this);
        interviewee = null;
        interviewer = null;
        Player stayed = other == null ? null : Bukkit.getPlayer(other);
        if (stayed != null) {
            sendBack(stayed);
            Msg.hint(stayed, player.getName() + " left, so the interview ended.");
        }
        save();
    }

    @Override public void forceStop(String reason) {
        if (active()) end();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tasks.later(5, () -> { if (player.isOnline() && plugin.getRegistry().isFree(player.getUniqueId())) sendBack(player); });
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        returns.forEach((id, saved) -> {
            yaml.set("returns." + id + ".location", saved.location());
            yaml.set("returns." + id + ".mode", saved.mode().name());
        });
        try { YamlStorage.save(yaml, file); }
        catch (RuntimeException failure) { plugin.getLogger().warning("Could not save interview.yml: " + failure.getMessage()); }
    }
}
