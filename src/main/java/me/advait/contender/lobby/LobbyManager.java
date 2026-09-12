package me.advait.contender.lobby;

import me.advait.contender.Contender;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class LobbyManager {

    private final Contender plugin;
    private String unavailableWorld;

    public LobbyManager(Contender plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    public Location getLobbyLocation() {
        World world = resolveLobbyWorld();
        if (world == null) {
            String worldName = getLobbyWorldName();
            if (!worldName.equals(unavailableWorld)) {
                plugin.getLogger().warning("Lobby world '" + worldName + "' is not loaded. Load it with Builder's /loadworld, "
                        + "or use /setlobby in the intended world. Using the default world spawn until it is available.");
                unavailableWorld = worldName;
            }
            World fallback = fallbackWorld();
            return fallback == null ? null : fallback.getSpawnLocation();
        }
        unavailableWorld = null;
        double x = plugin.getConfig().getDouble("lobby.x", 0.5);
        double y = plugin.getConfig().getDouble("lobby.y", 64.0);
        double z = plugin.getConfig().getDouble("lobby.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0.0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    private World resolveLobbyWorld() {
        World world = Bukkit.getWorld(getLobbyWorldName());
        if (world == null) {
            NamespacedKey key = NamespacedKey.fromString(plugin.getConfig().getString("lobby.world-key", getLobbyWorldName()));
            if (key != null) world = Bukkit.getWorld(key);
        }
        return allowedLobbyWorld(world) ? world : null;
    }

    private World fallbackWorld() {
        return Bukkit.getWorlds().stream().filter(this::allowedLobbyWorld).findFirst().orElse(null);
    }

    private boolean allowedLobbyWorld(World world) {
        return world != null && !plugin.getArenaManager().isArenaWorld(world)
                && (plugin.getMinigameManager() == null || !plugin.getMinigameManager().inArena(new Location(world, 0, 0, 0)));
    }
    public void setLobbyLocation(Location location) {
        if (!allowedLobbyWorld(location.getWorld())) {
            throw new IllegalArgumentException("Set the lobby outside the arena pool and minigame worlds.");
        }
        plugin.getConfig().set("lobby.world", location.getWorld().getName());
        plugin.getConfig().set("lobby.world-key", location.getWorld().getKey().toString());
        plugin.getConfig().set("lobby.x", location.getX()); plugin.getConfig().set("lobby.y", location.getY());
        plugin.getConfig().set("lobby.z", location.getZ()); plugin.getConfig().set("lobby.yaw", location.getYaw());
        plugin.getConfig().set("lobby.pitch", location.getPitch()); plugin.saveConfig();
    }

    public void returnToLobby(Player player) {
        if (plugin.getMinigameManager() != null) {
            if (plugin.getMinigameManager().leaveSpectating(player)) return;
            if (plugin.getMinigameManager().owns(player.getUniqueId())) throw new IllegalStateException("Ask a director to withdraw you before returning to the lobby.");
            if (plugin.getMinigameManager().pendingReturn(player.getUniqueId())) { plugin.getMinigameManager().restore(player); return; }
        }
        if (plugin.getRaceManager() != null && plugin.getRaceManager().owns(player.getUniqueId())) throw new IllegalStateException("Use /tournament to end the race, or ask a director to withdraw you first.");
        if (plugin.getRaceManager() != null && plugin.getRaceManager().pendingReturn(player.getUniqueId())) { plugin.getRaceManager().restore(player); return; }
        if (plugin.getDuelManager().isPlaying(player.getUniqueId())) throw new IllegalStateException("Wait for your match to finish before returning to the lobby.");
        if (plugin.getDuelManager().getDuel(player) != null) plugin.getDuelManager().leaveSpectating(player);
        else sendToLobby(player);
    }

    public String getLobbyWorldName() {
        return plugin.getConfig().getString("lobby.world", "world");
    }

    public boolean isLobbyWorld(World world) {
        World lobby = resolveLobbyWorld();
        return world != null && world.equals(lobby == null ? fallbackWorld() : lobby);
    }

    public boolean isAllowBlockBreak() {
        return plugin.getConfig().getBoolean("lobby.allow-block-break", false);
    }

    public boolean isAllowBlockPlace() {
        // Older configs used allow-block-break for both actions.
        return plugin.getConfig().contains("lobby.allow-block-place", true)
                ? plugin.getConfig().getBoolean("lobby.allow-block-place") : isAllowBlockBreak();
    }

    public boolean isAdminBuildBypass() { return plugin.getConfig().getBoolean("lobby.admin-build-bypass", true); }
    public boolean isTeleportOnJoin() { return plugin.getConfig().getBoolean("lobby.teleport-on-join", true); }
    public boolean canBreak(Player player) { return isAllowBlockBreak() || hasBuildBypass(player); }
    public boolean canPlace(Player player) { return isAllowBlockPlace() || hasBuildBypass(player); }
    private boolean hasBuildBypass(Player player) { return isAdminBuildBypass() && player.hasPermission("contender.admin"); }

    /** Called on the server thread from the asynchronous configuration event. */
    public Location getJoinLocation(java.util.UUID playerId) {
        if (plugin.getMinigameManager() != null && (plugin.getMinigameManager().owns(playerId) || plugin.getMinigameManager().pendingReturn(playerId))) return null;
        if (plugin.getRaceManager() != null && (plugin.getRaceManager().owns(playerId) || plugin.getRaceManager().pendingReturn(playerId))) return null;
        if (!isTeleportOnJoin() || plugin.getDuelManager().getDuel(playerId) != null) return null;
        return getLobbyLocation();
    }

    public void sendToLobby(Player player) {
        Location lobby = getLobbyLocation();
        if (lobby == null) return;
        if (!player.teleport(lobby)) {
            plugin.getLogger().warning("Could not teleport " + player.getName() + " to the lobby: the teleport was cancelled.");
            me.advait.contender.dialog.Dialogs.tell(player, "The lobby teleport was blocked. Try /lobby again.");
            return;
        }

        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setFireTicks(0);
        player.getInventory().clear();
        player.setHealth(Math.min(player.getMaxHealth(), 20.0));
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setLevel(0);
        player.setExp(0);
        player.setGameMode(plugin.getRoleManager().getRole(player.getUniqueId()) == me.advait.contender.role.PlayerRole.SPECTATOR
                ? GameMode.SPECTATOR : GameMode.SURVIVAL);
        if (plugin.getSpectatorControls() != null && plugin.getSpectatorControls().watching(player)) plugin.getSpectatorControls().giveCompass(player);
    }
}
