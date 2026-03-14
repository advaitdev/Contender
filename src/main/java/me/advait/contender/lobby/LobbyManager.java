package me.advait.contender.lobby;

import me.advait.contender.Contender;
import me.advait.contender.spectator.SpectatorManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

public class LobbyManager {

    private final Contender plugin;
    private final SpectatorManager spectatorManager;

    public LobbyManager(Contender plugin, SpectatorManager spectatorManager) {
        this.plugin = plugin;
        this.spectatorManager = spectatorManager;
        plugin.saveDefaultConfig();
    }

    public Location getLobbyLocation() {
        String worldName = plugin.getConfig().getString("lobby.world", "world");
        double x = plugin.getConfig().getDouble("lobby.x", 0.5);
        double y = plugin.getConfig().getDouble("lobby.y", 64.0);
        double z = plugin.getConfig().getDouble("lobby.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0.0);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = new WorldCreator(worldName).createWorld();
        }
        if (world == null) {
            plugin.getLogger().warning("Could not load lobby world '" + worldName + "'!");
            return null;
        }

        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getLobbyWorldName() {
        return plugin.getConfig().getString("lobby.world", "world");
    }

    public boolean isLobbyWorld(World world) {
        return world.getName().equals(getLobbyWorldName());
    }

    public boolean isAllowBlockBreak() {
        return plugin.getConfig().getBoolean("lobby.allow-block-break", false);
    }

    public void sendToLobby(Player player) {
        Location lobby = getLobbyLocation();
        if (lobby == null) return;

        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setFireTicks(0);
        player.getInventory().clear();
        player.setHealth(Math.min(player.getMaxHealth(), 20.0));
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setLevel(0);
        player.setExp(0);
        if (!spectatorManager.isDeceased(player)) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.teleport(lobby);
    }
}
