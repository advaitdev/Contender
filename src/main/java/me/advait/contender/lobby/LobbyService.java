package me.advait.contender.lobby;

import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** The saved lobby position and the settings that protect the lobby world. */
public final class LobbyService {
    private final Contender plugin;
    private String warnedWorld;

    public LobbyService(Contender plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    public String worldName() { return plugin.getConfig().getString("lobby.world", "world"); }

    /** The lobby, or the first non-arena world's spawn when the saved world cannot be loaded. */
    public Location location() {
        World world = resolveWorld();
        if (world == null && plugin.getManagedWorlds() != null) {
            try {
                World loaded = plugin.getManagedWorlds().loadExisting(worldName());
                if (allowed(loaded)) world = loaded;
            } catch (RuntimeException failure) {
                warnOnce("Lobby world '" + worldName() + "' could not be loaded: " + failure.getMessage());
            }
        }
        if (world == null) {
            warnOnce("Lobby world '" + worldName() + "' is not loaded. Using the default world spawn.");
            World fallback = Bukkit.getWorlds().stream().filter(this::allowed).findFirst().orElse(null);
            return fallback == null ? null : fallback.getSpawnLocation();
        }
        warnedWorld = null;
        var config = plugin.getConfig();
        return new Location(world, config.getDouble("lobby.x", 0.5), config.getDouble("lobby.y", 64),
                config.getDouble("lobby.z", 0.5), (float) config.getDouble("lobby.yaw"), (float) config.getDouble("lobby.pitch"));
    }

    private void warnOnce(String message) {
        if (worldName().equals(warnedWorld)) return;
        warnedWorld = worldName();
        plugin.getLogger().warning(message);
    }

    private World resolveWorld() {
        World world = Bukkit.getWorld(worldName());
        if (world == null) {
            NamespacedKey key = NamespacedKey.fromString(plugin.getConfig().getString("lobby.world-key", worldName()));
            if (key != null) world = Bukkit.getWorld(key);
        }
        return allowed(world) ? world : null;
    }

    private boolean allowed(World world) {
        return world != null && (plugin.getArenas() == null || !plugin.getArenas().isArenaWorld(world));
    }

    public void setLocation(Location location) {
        if (!allowed(location.getWorld())) throw new IllegalArgumentException("Set the lobby outside the arena world.");
        var config = plugin.getConfig();
        config.set("lobby.world", location.getWorld().getName());
        config.set("lobby.world-key", location.getWorld().getKey().toString());
        config.set("lobby.x", location.getX());
        config.set("lobby.y", location.getY());
        config.set("lobby.z", location.getZ());
        config.set("lobby.yaw", location.getYaw());
        config.set("lobby.pitch", location.getPitch());
        plugin.saveConfig();
    }

    public boolean isLobbyWorld(World world) {
        if (world == null) return false;
        World lobby = resolveWorld();
        if (lobby != null) return world.equals(lobby);
        Location fallback = location();
        return fallback != null && world.equals(fallback.getWorld());
    }

    /**
     * Teleports the player to the lobby and resets their combat state. Inventories are left alone:
     * activities restore the player's own inventory from a snapshot after this call.
     */
    public boolean send(Player player) {
        Location lobby = location();
        if (lobby == null) return false;
        if (player.isDead()) return false;
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (!player.teleport(lobby)) return false;
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setVelocity(new org.bukkit.util.Vector());
        applyRoleMode(player);
        return true;
    }

    /** Lobby game mode for the player's role. Creative and adventure players keep their mode. */
    public void applyRoleMode(Player player) {
        PlayerRole role = plugin.getRoleManager().getRole(player.getUniqueId());
        if (role == PlayerRole.SPECTATOR) { player.setGameMode(GameMode.SPECTATOR); return; }
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
    }

    public boolean teleportOnJoin() { return plugin.getConfig().getBoolean("lobby.teleport-on-join", true); }
    public boolean allowBlockBreak() { return plugin.getConfig().getBoolean("lobby.allow-block-break", false); }
    public boolean allowBlockPlace() {
        var config = plugin.getConfig();
        return config.contains("lobby.allow-block-place", true) ? config.getBoolean("lobby.allow-block-place") : allowBlockBreak();
    }
    public boolean adminBuildBypass() { return plugin.getConfig().getBoolean("lobby.admin-build-bypass", true); }
    public boolean canBreak(Player player) { return allowBlockBreak() || bypass(player); }
    public boolean canPlace(Player player) { return allowBlockPlace() || bypass(player); }
    private boolean bypass(Player player) { return adminBuildBypass() && player.hasPermission("contender.admin"); }
}
