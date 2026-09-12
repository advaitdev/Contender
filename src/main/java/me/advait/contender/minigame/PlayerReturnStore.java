package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.util.YamlStorage;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import java.io.File;
import java.util.*;

/** Write-ahead inventory snapshots also survive a restart or an offline finish. */
public class PlayerReturnStore {
    private final Contender plugin;
    private final File file;
    private final YamlConfiguration data;
    public PlayerReturnStore(Contender plugin, String filename) {
        this.plugin = plugin; file = new File(plugin.getDataFolder(), filename);
        data = YamlConfiguration.loadConfiguration(file);
    }
    public boolean pending(UUID id) { return data.isConfigurationSection(id.toString()); }
    public void capture(Player player) {
        String key = player.getUniqueId().toString();
        if (pending(player.getUniqueId())) throw new IllegalStateException("Return " + player.getName() + " to the lobby before starting another event.");
        data.set(key + ".items", Arrays.stream(player.getInventory().getContents()).map(item -> item == null ? null : item.clone()).toList());
        data.set(key + ".mode", player.getGameMode().name());
        data.set(key + ".health", player.getHealth()); data.set(key + ".food", player.getFoodLevel());
        data.set(key + ".saturation", player.getSaturation()); data.set(key + ".exhaustion", player.getExhaustion());
        data.set(key + ".level", player.getLevel()); data.set(key + ".exp", player.getExp());
        data.set(key + ".effects", new ArrayList<>(player.getActivePotionEffects()));
        data.set(key + ".flight", player.getAllowFlight()); data.set(key + ".flying", player.isFlying());
        data.set(key + ".slot", player.getInventory().getHeldItemSlot());
        try { save(); } catch (RuntimeException failure) { data.set(key, null); throw failure; }
    }
    public boolean restore(Player player) {
        String key = player.getUniqueId().toString();
        if (!pending(player.getUniqueId())) return true;
        if (player.isDead()) return false;
        var lobby = plugin.getLobbyManager().getLobbyLocation();
        if (lobby == null || !player.teleport(lobby)) return false;
        List<?> items = data.getList(key + ".items", List.of());
        player.getInventory().setContents(items.stream().map(i -> (ItemStack) i).toArray(ItemStack[]::new));
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        for (Object effect : data.getList(key + ".effects", List.of())) if (effect instanceof PotionEffect potion) player.addPotionEffect(potion);
        player.setGameMode(plugin.getRoleManager().getRole(player.getUniqueId()) == me.advait.contender.role.PlayerRole.SPECTATOR
                ? GameMode.SPECTATOR : GameMode.valueOf(data.getString(key + ".mode", "SURVIVAL")));
        player.setAllowFlight(player.getGameMode() == GameMode.SPECTATOR || data.getBoolean(key + ".flight"));
        if (player.getAllowFlight()) player.setFlying(data.getBoolean(key + ".flying") || player.getGameMode() == GameMode.SPECTATOR);
        player.setHealth(Math.max(1, Math.min(player.getMaxHealth(), data.getDouble(key + ".health", 20))));
        player.setFoodLevel(data.getInt(key + ".food", 20)); player.setSaturation((float) data.getDouble(key + ".saturation", 5));
        player.setExhaustion((float) data.getDouble(key + ".exhaustion"));
        player.setLevel(data.getInt(key + ".level")); player.setExp((float) data.getDouble(key + ".exp"));
        player.getInventory().setHeldItemSlot(data.getInt(key + ".slot")); player.setFallDistance(0); player.setFireTicks(0);
        player.sendActionBar(net.kyori.adventure.text.Component.empty());
        // Persist removal only after restoration. Reapplying a snapshot is safe if the disk write fails.
        var saved = data.getConfigurationSection(key); data.set(key, null);
        try { save(); } catch (RuntimeException failure) { data.set(key, saved); throw failure; }
        return true;
    }
    private void save() { YamlStorage.save(data, file); }
}
