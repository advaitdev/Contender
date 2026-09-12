package me.advait.contender.hacker;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import me.advait.contender.Contender;
import me.advait.contender.dialog.HackerDialogs;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.util.YamlStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.time.Duration;
import java.util.*;

public final class HackerManager extends AbstractGameState {
    private static final Title.Times REVEAL_TIMES = Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(600));
    public record Profile(String name, HackSettings settings, UUID selection, boolean announce) { }
    private final Contender contender;
    private final File file;
    private final Map<UUID, Profile> hackers = new LinkedHashMap<>();
    private final HackerDialogs dialogs;

    public HackerManager(Contender plugin) {
        super(plugin); contender = plugin; file = new File(plugin.getDataFolder(), "hackers.yml");
        var yaml = YamlConfiguration.loadConfiguration(file);
        var entries = yaml.getConfigurationSection("players");
        if (entries != null) for (String key : entries.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                var values = new EnumMap<HackSetting, Double>(HackSetting.class);
                for (var setting : HackSetting.values()) values.put(setting, entries.getDouble(key + ".settings." + setting.key(), setting.normal));
                hackers.put(id, new Profile(entries.getString(key + ".name", key), new HackSettings(values), UUID.randomUUID(), entries.getBoolean(key + ".announce")));
            } catch (IllegalArgumentException invalid) { plugin.getLogger().warning("Invalid hacker entry: " + key + ": " + invalid.getMessage()); }
        }
        dialogs = new HackerDialogs(plugin, this);
    }
    public HackerDialogs dialogs() { return dialogs; }
    public Profile profile(UUID id) { return hackers.get(id); }
    public boolean isHacker(UUID id) { return hackers.containsKey(id) && contender.getRoleManager().isContestant(id); }
    public boolean hasActiveHacks(Player player) {
        if (!isEnabled() || !isHacker(player.getUniqueId())) return false;
        var duel = contender.getDuelManager().getDuel(player);
        if (contender.getMinigameManager() != null && contender.getMinigameManager().isPlaying(player.getUniqueId())) return true;
        if (contender.getRaceManager() != null && contender.getRaceManager().isRacing(player.getUniqueId())) return true;
        return duel != null && duel.isCombatActive() && duel.isInDuel(player.getUniqueId()) && !duel.isSpectator(player.getUniqueId());
    }
    public void refresh() {
        for (Player player : contender.getServer().getOnlinePlayers()) {
            HackerAttributes.apply(player, hasActiveHacks(player) ? profile(player.getUniqueId()).settings() : null);
        }
    }
    @Override protected void onEnable() {
        refresh();
        for (Player player : contender.getServer().getOnlinePlayers()) { player.updateCommands(); announce(player); }
        runRepeating(this::refresh, 20, 20);
    }
    @Override protected void onDisable() {
        for (Player player : contender.getServer().getOnlinePlayers()) HackerAttributes.apply(player, null);
        dialogs.clear();
    }
    /** Validate the full roster before changing any assignment. Picking again replaces the roster. */
    public void pick(List<? extends OfflinePlayer> players) {
        var updated = new LinkedHashMap<UUID, Profile>();
        for (OfflinePlayer player : players) {
            UUID id = player.getUniqueId();
            if (!contender.getRoleManager().isContestant(id)) throw new IllegalArgumentException(player.getName() + " must be a contestant first.");
            if (updated.containsKey(id)) throw new IllegalArgumentException("Only enter each player once.");
            var old = hackers.get(id);
            updated.put(id, new Profile(Objects.requireNonNull(player.getName()), old == null ? HackSettings.defaults() : old.settings(), UUID.randomUUID(), true));
        }
        save(updated);
        hackers.clear(); hackers.putAll(updated); dialogs.clear(); refresh();
        for (Player player : contender.getServer().getOnlinePlayers()) {
            player.updateCommands();
            if (!players.isEmpty() && !isHacker(player.getUniqueId())) {
                player.showTitle(Title.title(Component.text("You are not the hacker.", NamedTextColor.GREEN), Component.empty(), REVEAL_TIMES));
            } else announce(player);
        }
    }
    public void update(Player player, UUID selection, HackSettings settings) {
        var current = profile(player.getUniqueId());
        if (!isHacker(player.getUniqueId()) || current == null || !current.selection().equals(selection)) {
            throw new IllegalStateException("Your hacker selection has changed. Open /hacks again.");
        }
        var updated = new LinkedHashMap<>(hackers);
        updated.put(player.getUniqueId(), new Profile(current.name(), settings, current.selection(), current.announce()));
        save(updated); hackers.clear(); hackers.putAll(updated); refresh();
    }
    private void save(Map<UUID, Profile> profiles) {
        var yaml = new YamlConfiguration();
        profiles.forEach((id, profile) -> {
            String path = "players." + id;
            yaml.set(path + ".name", profile.name()); yaml.set(path + ".announce", profile.announce());
            for (var setting : HackSetting.values()) yaml.set(path + ".settings." + setting.key(), profile.settings().get(setting));
        });
        YamlStorage.save(yaml, file);
    }
    private void announce(Player player) {
        var profile = profile(player.getUniqueId());
        if (!isHacker(player.getUniqueId()) || profile == null || !profile.announce()) return;
        List<String> teammates = hackers.entrySet().stream().filter(entry -> !entry.getKey().equals(player.getUniqueId()) && isHacker(entry.getKey()))
                .map(entry -> entry.getValue().name()).toList();
        Component subtitle = teammates.isEmpty() ? Component.empty() : Component.text("Your teammates: " + String.join(", ", teammates), NamedTextColor.RED);
        player.showTitle(Title.title(Component.text("You are the hacker.", NamedTextColor.RED), subtitle, REVEAL_TIMES));
        var updated = new LinkedHashMap<>(hackers);
        updated.put(player.getUniqueId(), new Profile(profile.name(), profile.settings(), profile.selection(), false));
        save(updated); hackers.clear(); hackers.putAll(updated);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        runLater(() -> {
            if (!event.getPlayer().isOnline()) return;
            refresh(); event.getPlayer().updateCommands(); announce(event.getPlayer());
        }, 1);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { HackerAttributes.apply(event.getPlayer(), null); dialogs.forget(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { runLater(this::refresh, 1); }
    @EventHandler public void onQuickAction(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().equals(me.advait.contender.ContenderBootstrap.OPEN_HACKS)) return;
        if (event.getCommonConnection() instanceof PlayerGameConnection connection) {
            Player player = connection.getPlayer();
            runLater(() -> { if (player.isOnline()) dialogs.open(player); }, 1);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && hasActiveHacks(player) && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            double resistance = profile(player.getUniqueId()).settings().get(HackSetting.RESISTANCE);
            if (resistance > 0) HackerResistance.apply(event, player, resistance);
        }
    }
}
