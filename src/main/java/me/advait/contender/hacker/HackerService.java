package me.advait.contender.hacker;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.role.PlayerRole;
import me.advait.contender.stage.Stage;
import me.advait.contender.stage.StageService;
import me.advait.contender.util.YamlStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.*;

/**
 * The secret hackers and their abilities.
 *
 * In SELF mode each hacker sets their own hacks with /hacks, and they stay on everywhere. In DIRECTOR mode
 * the director chooses each event's hacks; they switch on when the event starts and off when it ends,
 * and hackers can only see what they were given.
 */
public final class HackerService extends Module implements StageService.Listener {
    public enum Mode { SELF, DIRECTOR }
    public record Profile(String name, HackSettings own) { }

    private final File file;
    private final Map<UUID, Profile> hackers = new LinkedHashMap<>();
    private final Map<UUID, HackSettings> plans = new HashMap<>();
    private final Set<UUID> unannounced = new HashSet<>();
    private HackSettings sharedPlan = HackSettings.defaults();
    private Mode mode = Mode.SELF;
    private boolean planActive;
    /** In DIRECTOR mode, whether hackers use (and can change) their own hacks between events. */
    private boolean ownBetweenEvents = true;

    public HackerService(Contender plugin) {
        super(plugin);
        file = new File(plugin.getDataFolder(), "hackers.yml");
        load();
    }

    @Override protected void onEnable() {
        plugin.getStages().listen(this);
        refresh();
        for (Player player : plugin.getServer().getOnlinePlayers()) { player.updateCommands(); announce(player); }
        tasks.repeat(20, 20, () -> { refresh(); regenerate(); });
    }

    @Override protected void onDisable() {
        for (Player player : plugin.getServer().getOnlinePlayers()) HackerAttributes.apply(player, null);
    }

    // ---- Queries -------------------------------------------------------------------------------

    public Mode mode() { return mode; }
    public boolean planActive() { return planActive; }
    public boolean ownBetweenEvents() { return ownBetweenEvents; }

    /** Whether hackers can change their own hacks right now: always when they pick, or between events if allowed. */
    public boolean choosingOwn() { return mode == Mode.SELF || !planActive && ownBetweenEvents; }
    public HackSettings sharedPlan() { return sharedPlan; }
    public HackSettings plan(UUID hacker) { return plans.getOrDefault(hacker, sharedPlan); }
    public boolean hasOwnPlan(UUID hacker) { return plans.containsKey(hacker); }
    public Map<UUID, Profile> hackers() { return Collections.unmodifiableMap(hackers); }
    public Profile profile(UUID id) { return hackers.get(id); }

    /** Selected and still a contestant (directors and spectators keep their selection but lose the hacks). */
    public boolean isHacker(UUID id) {
        return hackers.containsKey(id) && plugin.getRoleManager().getRole(id) == PlayerRole.CONTESTANT;
    }

    /** What is actually applied to this player right now, or null for nothing. */
    public HackSettings effective(UUID id) {
        if (!isEnabled() || !isHacker(id)) return null;
        if (mode == Mode.SELF) return hackers.get(id).own();
        if (planActive) return plan(id);
        return ownBetweenEvents ? hackers.get(id).own() : null;
    }

    public void refresh() {
        if (!isEnabled()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) HackerAttributes.apply(player, effective(player.getUniqueId()));
    }

    // ---- Changes -------------------------------------------------------------------------------

    /**
     * Replaces the selection. Everyone online is told whether they are a hacker; offline hackers are told on join.
     * Only new picks must be contestants: a hacker who was voted out or left can stay picked or be removed.
     */
    public void pick(List<? extends OfflinePlayer> players) {
        Map<UUID, Profile> updated = new LinkedHashMap<>();
        for (OfflinePlayer player : players) {
            UUID id = player.getUniqueId();
            Profile old = hackers.get(id);
            String name = player.getName() != null ? player.getName() : old != null ? old.name() : null;
            if (name == null) throw new IllegalArgumentException("Couldn't find one of those players. They need to join the server once first.");
            if (old == null && plugin.getRoleManager().getRole(id) != PlayerRole.CONTESTANT) throw new IllegalArgumentException(name + " must be a contestant first.");
            if (updated.containsKey(id)) throw new IllegalArgumentException("Enter each player once.");
            updated.put(id, new Profile(name, old == null ? HackSettings.defaults() : old.own()));
        }
        hackers.clear();
        hackers.putAll(updated);
        plans.keySet().retainAll(updated.keySet());
        unannounced.clear();
        unannounced.addAll(updated.keySet());
        save();
        refresh();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.updateCommands();
            if (!players.isEmpty() && !hackers.containsKey(player.getUniqueId())
                    && plugin.getRoleManager().getRole(player.getUniqueId()) == PlayerRole.CONTESTANT) {
                Msg.title(player, Component.text("You are not the hacker.", NamedTextColor.GREEN), Component.empty(), 6, 100, 12);
            } else announce(player);
        }
    }

    public void setOwn(Player player, HackSettings settings) {
        Profile current = hackers.get(player.getUniqueId());
        if (current == null || !isHacker(player.getUniqueId())) throw new IllegalStateException("Only hackers can change hacks.");
        if (!choosingOwn()) throw new IllegalStateException("The director is choosing hacks for this event.");
        hackers.put(player.getUniqueId(), new Profile(current.name(), settings));
        save();
        refresh();
    }

    public void setMode(Mode next) {
        mode = next;
        planActive = next == Mode.DIRECTOR && plugin.getStages().running();
        save();
        refresh();
        for (UUID id : hackers.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !isHacker(id)) continue;
            Msg.hint(player, next == Mode.SELF ? "You can choose your own hacks again with /hacks."
                    : "The director now chooses your hacks for each event.");
        }
    }

    /** Sets the director's plan for one hacker, or for every hacker when {@code hacker} is null. */
    public void setPlan(UUID hacker, HackSettings settings) {
        if (hacker == null) sharedPlan = settings;
        else plans.put(hacker, settings);
        save();
        refresh();
        if (planActive) tellPlan(hacker == null ? hackers.keySet() : Set.of(hacker));
    }

    public void clearOwnPlan(UUID hacker) {
        plans.remove(hacker);
        save();
        refresh();
        if (planActive) tellPlan(Set.of(hacker));
    }

    /** In DIRECTOR mode: whether hackers use their own hacks between events, or none. */
    public void setOwnBetweenEvents(boolean allowed) {
        ownBetweenEvents = allowed;
        save();
        refresh();
        if (mode != Mode.DIRECTOR || planActive) return;
        for (UUID id : hackers.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && isHacker(id)) Msg.hint(player, allowed ? "Between events you can pick your own hacks with /hacks." : "Your hacks are off until the next event.");
        }
    }

    /** Turns the plan on or off without waiting for an event to start or end. */
    public void activatePlan(boolean active) {
        if (mode != Mode.DIRECTOR) throw new IllegalStateException("Switch to director-chosen hacks first.");
        if (planActive == active) return;
        planActive = active;
        save();
        refresh();
        if (active) tellPlan(hackers.keySet());
        else for (UUID id : hackers.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && isHacker(id)) Msg.hint(player, ownBetweenEvents
                    ? "The event's hacks are off. Your own hacks are back on; change them with /hacks." : "Your hacks are off.");
        }
    }

    @Override public void stageStarted(Stage stage) {
        if (mode == Mode.DIRECTOR && !planActive) activatePlan(true);
    }

    @Override public void stageEnded(Stage stage) {
        if (mode == Mode.DIRECTOR && planActive) activatePlan(false);
    }

    private void tellPlan(Collection<UUID> who) {
        for (UUID id : who) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !isHacker(id)) continue;
            HackSettings settings = plan(id);
            Msg.title(player, Component.text("Hacks on", DialogPalette.DANGER), Msg.text(settings.summary(), DialogPalette.TEXT), 5, 60, 10);
            player.sendMessage(Msg.text("Your hacks this event: ", DialogPalette.MUTED).append(Msg.text(settings.summary(), DialogPalette.ACCENT)));
            Sounds.SABOTAGE.play(player);
        }
    }

    private void announce(Player player) {
        UUID id = player.getUniqueId();
        if (!unannounced.contains(id) || !isHacker(id)) return;
        List<String> teammates = hackers.entrySet().stream().filter(entry -> !entry.getKey().equals(id) && isHacker(entry.getKey()))
                .map(entry -> entry.getValue().name()).toList();
        Component subtitle = teammates.isEmpty() ? Component.empty() : Component.text("Your teammates: " + String.join(", ", teammates), NamedTextColor.RED);
        Msg.title(player, Component.text("You are the hacker.", NamedTextColor.RED), subtitle, 6, 100, 12);
        player.sendMessage(Msg.text(mode == Mode.SELF ? "Open /hacks to choose your hacks. Only you can see them."
                : ownBetweenEvents ? "The director will choose your hacks for each event. Between events, pick your own with /hacks."
                : "The director will choose your hacks for each event. Check /hacks to see them.", DialogPalette.MUTED));
        unannounced.remove(id);
        save();
    }

    // ---- Persistence ---------------------------------------------------------------------------

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try { mode = Mode.valueOf(yaml.getString("mode", "SELF").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { mode = Mode.SELF; }
        planActive = false;
        ownBetweenEvents = yaml.getBoolean("own-between-events", true);
        sharedPlan = read(yaml.getConfigurationSection("plan.shared"));
        ConfigurationSection perPlayer = yaml.getConfigurationSection("plan.players");
        if (perPlayer != null) for (String key : perPlayer.getKeys(false)) {
            try { plans.put(UUID.fromString(key), read(perPlayer.getConfigurationSection(key))); }
            catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                hackers.put(id, new Profile(players.getString(key + ".name", key), read(players.getConfigurationSection(key + ".settings"))));
                if (players.getBoolean(key + ".announce")) unannounced.add(id);
            } catch (IllegalArgumentException invalid) {
                plugin.getLogger().warning("Skipping an invalid hacker entry: " + key);
            }
        }
    }

    private static HackSettings read(ConfigurationSection section) {
        if (section == null) return HackSettings.defaults();
        Map<HackSetting, Double> values = new EnumMap<>(HackSetting.class);
        for (HackSetting setting : HackSetting.values()) values.put(setting, section.getDouble(setting.key(), setting.normal));
        // Subtle reach used to be 3.3 blocks; it's 3.2 now, so saved Subtle picks follow it.
        if (Math.abs(values.get(HackSetting.REACH) - 3.3) < 1e-6) values.put(HackSetting.REACH, 3.2);
        return new HackSettings(values);
    }

    private static void write(YamlConfiguration yaml, String path, HackSettings settings) {
        for (HackSetting setting : HackSetting.values()) yaml.set(path + "." + setting.key(), settings.get(setting));
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mode", mode.name().toLowerCase(Locale.ROOT));
        yaml.set("own-between-events", ownBetweenEvents);
        write(yaml, "plan.shared", sharedPlan);
        plans.forEach((id, settings) -> write(yaml, "plan.players." + id, settings));
        hackers.forEach((id, profile) -> {
            yaml.set("players." + id + ".name", profile.name());
            yaml.set("players." + id + ".announce", unannounced.contains(id));
            write(yaml, "players." + id + ".settings", profile.own());
        });
        YamlStorage.save(yaml, file);
    }

    // ---- Effects that are not attributes -------------------------------------------------------

    private void regenerate() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            HackSettings settings = effective(player.getUniqueId());
            if (settings == null || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) continue;
            double perSecond = settings.get(HackSetting.REGENERATION) / 10.0;
            if (perSecond <= 0) continue;
            var max = player.getAttribute(Attribute.MAX_HEALTH);
            double limit = max == null ? 20 : max.getValue();
            if (player.getHealth() < limit) player.heal(perSecond, EntityRegainHealthEvent.RegainReason.MAGIC_REGEN);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        HackSettings settings = effective(player.getUniqueId());
        if (settings == null || event.getCause() == EntityDamageEvent.DamageCause.VOID) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            double reduction = settings.get(HackSetting.NO_FALL) / 100.0;
            if (reduction >= 1) { event.setCancelled(true); return; }
            if (reduction > 0) event.setDamage(event.getDamage() * (1 - reduction));
        }
        double resistance = settings.get(HackSetting.RESISTANCE);
        if (resistance > 0) HackerResistance.apply(event, player, resistance);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tasks.later(20, () -> {
            if (!player.isOnline()) return;
            refresh();
            player.updateCommands();
            announce(player);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { HackerAttributes.apply(event.getPlayer(), null); }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) { tasks.later(1, this::refresh); }
}
