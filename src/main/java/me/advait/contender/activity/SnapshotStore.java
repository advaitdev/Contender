package me.advait.contender.activity;

import me.advait.contender.Contender;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.util.YamlStorage;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Write-ahead copies of a player's inventory and state, taken before an activity changes them.
 * Saved to disk immediately, so a crash or disconnect never loses items: the snapshot is
 * restored when the activity returns the player, or on their next join.
 */
public final class SnapshotStore {
    private static final List<String> LEGACY_FILES = List.of("race-returns.yml", "combo-returns.yml", "manhunt-returns.yml");
    private final Contender plugin;
    private final File file;
    private final YamlConfiguration data;

    public SnapshotStore(Contender plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "snapshots.yml");
        data = YamlConfiguration.loadConfiguration(file);
        importLegacy();
    }

    /** Older versions kept one recovery file per minigame with the same layout. */
    private void importLegacy() {
        boolean changed = false;
        for (String name : LEGACY_FILES) {
            File legacy = new File(plugin.getDataFolder(), name);
            if (!legacy.isFile()) continue;
            YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
            for (String key : old.getKeys(false)) {
                if (!data.isConfigurationSection(key) && old.isConfigurationSection(key)) {
                    data.set(key, old.getConfigurationSection(key));
                    changed = true;
                }
            }
            if (!legacy.renameTo(new File(plugin.getDataFolder(), name + ".imported"))) {
                plugin.getLogger().warning("Could not rename " + name + " after importing it.");
            }
        }
        if (changed) save();
    }

    public boolean has(UUID player) { return data.isConfigurationSection(player.toString()); }

    /** Keeps an existing snapshot: the oldest one is the player's real state before any event. */
    public void capture(Player player) {
        String key = player.getUniqueId().toString();
        if (has(player.getUniqueId())) return;
        // Closing the view returns cursor and crafting-grid items to the inventory first.
        player.closeInventory();
        data.set(key + ".items", Arrays.stream(player.getInventory().getContents()).map(item -> item == null ? null : item.clone()).toList());
        data.set(key + ".mode", player.getGameMode().name());
        data.set(key + ".health", player.getHealth());
        data.set(key + ".food", player.getFoodLevel());
        data.set(key + ".saturation", player.getSaturation());
        data.set(key + ".exhaustion", player.getExhaustion());
        data.set(key + ".level", player.getLevel());
        data.set(key + ".exp", player.getExp());
        data.set(key + ".effects", new ArrayList<>(player.getActivePotionEffects()));
        data.set(key + ".flight", player.getAllowFlight());
        data.set(key + ".flying", player.isFlying());
        data.set(key + ".slot", player.getInventory().getHeldItemSlot());
        try { save(); }
        catch (RuntimeException failure) { data.set(key, null); throw failure; }
    }

    /** Restores in place. Returns false if there was nothing to restore or the player cannot receive it yet. */
    public boolean restore(Player player) {
        String key = player.getUniqueId().toString();
        ConfigurationSection saved = data.getConfigurationSection(key);
        if (saved == null || player.isDead()) return false;
        player.closeInventory();
        player.setItemOnCursor(null);
        List<?> items = saved.getList("items", List.of());
        ItemStack[] contents = new ItemStack[player.getInventory().getSize()];
        for (int i = 0; i < Math.min(items.size(), contents.length); i++) {
            if (items.get(i) instanceof ItemStack item) contents[i] = item;
        }
        player.getInventory().setContents(contents);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        for (Object effect : saved.getList("effects", List.of())) {
            if (effect instanceof PotionEffect potion) player.addPotionEffect(potion);
        }
        GameMode mode;
        try { mode = GameMode.valueOf(saved.getString("mode", "SURVIVAL")); }
        catch (IllegalArgumentException invalid) { mode = GameMode.SURVIVAL; }
        if (plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.SPECTATOR) mode = GameMode.SPECTATOR;
        else if (mode == GameMode.SPECTATOR) mode = GameMode.SURVIVAL;
        player.setGameMode(mode);
        boolean flight = mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || saved.getBoolean("flight");
        player.setAllowFlight(flight);
        if (flight) player.setFlying(mode == GameMode.SPECTATOR || saved.getBoolean("flying"));
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20 : maxHealth.getValue();
        player.setHealth(Math.max(1, Math.min(max, saved.getDouble("health", max))));
        player.setFoodLevel(saved.getInt("food", 20));
        player.setSaturation((float) saved.getDouble("saturation", 5));
        player.setExhaustion((float) saved.getDouble("exhaustion"));
        player.setLevel(saved.getInt("level"));
        player.setExp((float) Math.clamp(saved.getDouble("exp"), 0, 0.9999));
        player.getInventory().setHeldItemSlot(Math.clamp(saved.getInt("slot"), 0, 8));
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        data.set(key, null);
        try { save(); }
        catch (RuntimeException failure) {
            // The player already has their items. Keeping the record would duplicate them on the next join.
            plugin.getLogger().log(Level.SEVERE, "Restored " + player.getName() + " but could not update snapshots.yml", failure);
        }
        return true;
    }

    /** Sends the player to the lobby, then restores their snapshot there. */
    public void restoreToLobby(Player player) {
        if (!plugin.getLobby().send(player)) {
            plugin.getLogger().warning("Could not return " + player.getName() + " to the lobby; their saved inventory is kept for later.");
            return;
        }
        restore(player);
    }

    /** Drops a snapshot without applying it (for example when an admin resets a player by hand). */
    public void discard(UUID player) {
        if (!has(player)) return;
        data.set(player.toString(), null);
        save();
    }

    private void save() { YamlStorage.save(data, file); }
}
