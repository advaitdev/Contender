package me.advait.contender.sabotage;

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
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/**
 * Sabotages: rule changes a hacker can trigger during an event. Everything is set in sabotages.yml or
 * from /hackers → Sabotage Settings: which sabotages exist, how often hackers may use them, how long they
 * last, and whether hackers are affected too.
 */
public final class SabotageService extends Module implements StageService.Listener {
    /** A sabotage that is currently running. {@code endsAt} is 0 when it lasts until the event ends. */
    public record Active(Sabotage sabotage, UUID triggeredBy, long endsAt, SabotageContext context, Set<UUID> applied, Scope scope) { }

    /**
     * Who a sabotage hits: everyone in the current event, or the players in one duel that isn't part of an event.
     * Uses, cooldowns and the running limit are counted per scope.
     */
    public record Scope(String key, me.advait.contender.duel.Duel duel) {
        public static final Scope EVENT = new Scope("event", null);
        static Scope of(me.advait.contender.duel.Duel duel) { return new Scope("duel-" + duel.id(), duel); }
        /** The key a running sabotage is stored under; event sabotages keep their plain id. */
        String key(Sabotage sabotage) { return duel == null ? sabotage.id() : key + ":" + sabotage.id(); }
        String noun() { return duel == null ? "event" : "duel"; }
    }

    private final File file;
    private YamlConfiguration config;
    private LagInjector lag;
    private Map<String, Sabotage> all = Map.of();
    private final Map<String, Active> active = new LinkedHashMap<>();
    /** Sabotages used, by scope key and then hacker. */
    private final Map<String, Map<UUID, Integer>> uses = new HashMap<>();
    /** When each scope last had a sabotage started. */
    private final Map<String, Long> lastTrigger = new HashMap<>();
    private long seconds;

    public SabotageService(Contender plugin) {
        super(plugin);
        file = new File(plugin.getDataFolder(), "sabotages.yml");
    }

    @Override protected void onEnable() {
        lag = new LagInjector(plugin.getLogger());
        all = Sabotages.all(lag);
        load();
        plugin.getStages().listen(this);
        tasks.repeat(20, 20, this::tick);
    }

    @Override protected void onDisable() {
        for (String id : List.copyOf(active.keySet())) end(id, false);
    }

    // ---- Settings ------------------------------------------------------------------------------

    private void load() {
        config = YamlConfiguration.loadConfiguration(file);
        config.addDefault("enabled", false);
        config.addDefault("affects-hackers", true);
        config.addDefault("duration-seconds", 0);
        config.addDefault("uses-per-hacker", 1);
        config.addDefault("cooldown-seconds", 30);
        config.addDefault("max-active", 3);
        config.addDefault("reveal-hacker", false);
        config.addDefault("settings.lag_spike.ping", 200);
        config.addDefault("settings.vampire.heal-share", 0.35);
        config.addDefault("settings.fragile.multiplier", 1.5);
        config.addDefault("settings.butterfingers.interval-seconds", 20);
        config.addDefault("settings.switcheroo.interval-seconds", 25);
        config.addDefault("settings.blackout.interval-seconds", 15);
        config.options().copyDefaults(true);
        if (!config.contains("allowed")) config.set("allowed", new ArrayList<>(all.keySet()));
        save();
    }

    private void save() {
        try { YamlStorage.save(config, file); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.WARNING, "Could not save sabotages.yml", failure); }
    }

    public boolean enabled() { return config.getBoolean("enabled"); }
    public boolean affectsHackers() { return config.getBoolean("affects-hackers"); }
    /** 0 means until the event ends. */
    public int durationSeconds() { return Math.clamp(config.getInt("duration-seconds"), 0, 3600); }
    public int usesPerHacker() { return Math.clamp(config.getInt("uses-per-hacker"), 0, 20); }
    public int cooldownSeconds() { return Math.clamp(config.getInt("cooldown-seconds"), 0, 600); }
    public int maxActive() { return Math.clamp(config.getInt("max-active"), 1, 10); }
    public boolean revealHacker() { return config.getBoolean("reveal-hacker"); }
    public boolean allowed(String id) { return config.getStringList("allowed").contains(id); }

    public void configure(boolean enabled, boolean affectsHackers, int duration, int uses, int cooldown, int maxActive, boolean reveal) {
        config.set("enabled", enabled);
        config.set("affects-hackers", affectsHackers);
        config.set("duration-seconds", Math.clamp(duration, 0, 3600));
        config.set("uses-per-hacker", Math.clamp(uses, 0, 20));
        config.set("cooldown-seconds", Math.clamp(cooldown, 0, 600));
        config.set("max-active", Math.clamp(maxActive, 1, 10));
        config.set("reveal-hacker", reveal);
        save();
    }

    public void setAllowed(String id, boolean allowed) {
        List<String> list = new ArrayList<>(config.getStringList("allowed"));
        list.remove(id);
        if (allowed) list.add(id);
        config.set("allowed", list);
        save();
    }

    public Collection<Sabotage> all() { return all.values(); }
    public Sabotage byId(String id) { return all.get(id); }
    public Map<String, Active> active() { return Collections.unmodifiableMap(active); }
    /** Where this hacker's sabotages would land: their event, their duel, or nowhere (null). */
    public Scope scopeFor(Player hacker) {
        UUID id = hacker.getUniqueId();
        Stage stage = plugin.getStages().current();
        if (plugin.getStages().running() && stage != null && stage.involves(id)) return Scope.EVENT;
        var duel = plugin.getDuels().duelOf(id);
        if (duel != null && duel.isPlayer(id) && !duel.isOver()) return Scope.of(duel);
        return plugin.getStages().running() ? Scope.EVENT : null;
    }

    public int usesLeft(Player hacker) {
        Scope scope = scopeFor(hacker);
        int used = scope == null ? 0 : uses.getOrDefault(scope.key(), Map.of()).getOrDefault(hacker.getUniqueId(), 0);
        return Math.max(0, usesPerHacker() - used);
    }

    private long cooldownLeft(Scope scope) {
        long left = lastTrigger.getOrDefault(scope.key(), 0L) + cooldownSeconds() * 1000L - System.currentTimeMillis();
        return Math.max(0, (left + 999) / 1000);
    }

    private long runningIn(Scope scope) { return active.values().stream().filter(running -> running.scope().equals(scope)).count(); }

    /** Whether this hacker can open the sabotage menu right now. */
    public boolean available(Player player) {
        return isEnabled() && enabled() && plugin.getHackers().isHacker(player.getUniqueId());
    }

    /** Explains why a hacker cannot trigger a sabotage now, or returns null when they can. */
    public String blocked(Player hacker, Sabotage sabotage) {
        if (!enabled()) return "Only the director can start sabotages right now.";
        if (!plugin.getHackers().isHacker(hacker.getUniqueId())) return "Only hackers can sabotage.";
        Scope scope = scopeFor(hacker);
        if (scope == null) return "Sabotages work during an event or a duel.";
        if (sabotage != null && !allowed(sabotage.id())) return "That sabotage is turned off.";
        if (sabotage != null && active.containsKey(scope.key(sabotage))) return sabotage.name() + " is already running.";
        if (usesLeft(hacker) <= 0) return "You've used all your sabotages for this " + scope.noun() + ".";
        if (runningIn(scope) >= maxActive()) return "Too many sabotages are running already.";
        long cooldown = cooldownLeft(scope);
        if (cooldown > 0) return "Wait " + cooldown + "s before the next sabotage.";
        return null;
    }

    // ---- Triggering ----------------------------------------------------------------------------

    public void trigger(Player hacker, Sabotage sabotage) {
        String reason = blocked(hacker, sabotage);
        if (reason != null) throw new IllegalStateException(reason);
        Scope scope = scopeFor(hacker);
        uses.computeIfAbsent(scope.key(), ignored -> new HashMap<>()).merge(hacker.getUniqueId(), 1, Integer::sum);
        begin(sabotage, hacker.getUniqueId(), scope);
    }

    /** Directors can start any sabotage, ignoring uses and cooldowns. */
    public void force(Sabotage sabotage, UUID by) {
        if (active.containsKey(sabotage.id())) throw new IllegalStateException(sabotage.name() + " is already running.");
        begin(sabotage, by, Scope.EVENT);
    }

    private void begin(Sabotage sabotage, UUID by, Scope scope) {
        ConfigurationSection settings = config.getConfigurationSection("settings." + sabotage.id());
        SabotageContext context = new SabotageContext(plugin, settings, id -> affected(id, scope));
        long endsAt = durationSeconds() == 0 ? 0 : System.currentTimeMillis() + durationSeconds() * 1000L;
        Active running = new Active(sabotage, by, endsAt, context, new HashSet<>(), scope);
        active.put(scope.key(sabotage), running);
        lastTrigger.put(scope.key(), System.currentTimeMillis());
        safely(running, () -> sabotage.start(context));
        sync(running);
        safely(running, () -> announce(running));
    }

    private void announce(Active running) {
        Sabotage sabotage = running.sabotage();
        Player trigger = running.triggeredBy() == null ? null : Bukkit.getPlayer(running.triggeredBy());
        String who = trigger == null ? "A director" : trigger.getName();
        Component subtitle = Msg.text(sabotage.description(), DialogPalette.TEXT);
        Component title = Msg.text("Sabotage: " + sabotage.name(), DialogPalette.DANGER);
        Set<UUID> audience = audience(running.scope());
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean director = player.hasPermission("contender.master");
            // A duel's sabotage is only news to that duel; directors still get the chat line.
            if (audience != null && !audience.contains(player.getUniqueId()) && !director) continue;
            if (audience == null || audience.contains(player.getUniqueId())) {
                Msg.title(player, title, subtitle, 5, 60, 15);
                Sounds.SABOTAGE.play(player);
            }
            Component line = Msg.text("Sabotage! ", DialogPalette.DANGER).append(Msg.text(sabotage.name(), DialogPalette.ACCENT))
                    .append(Msg.text(" · " + sabotage.description(), DialogPalette.MUTED));
            if (revealHacker() || director) line = line.append(Msg.text(" (" + who + ")", DialogPalette.MUTED));
            player.sendMessage(line);
        }
        plugin.getLogger().info("Sabotage " + sabotage.id() + " started by " + who + (running.scope().duel() == null ? "" : " in " + running.scope().duel().displayName()));
    }

    /** Ends a sabotage early (director control) or when its time runs out. */
    public void end(String id, boolean announce) {
        Active running = active.remove(id);
        if (running == null) return;
        for (UUID affected : List.copyOf(running.applied())) {
            Player player = Bukkit.getPlayer(affected);
            if (player != null) safely(running, () -> running.sabotage().remove(player, running.context()));
        }
        running.applied().clear();
        safely(running, () -> running.sabotage().stop(running.context()));
        if (!announce) return;
        Component line = Msg.text(running.sabotage().name() + " wore off.", DialogPalette.MUTED);
        Set<UUID> audience = audience(running.scope());
        if (audience == null) Msg.broadcast(line);
        else for (UUID member : audience) { Player player = Bukkit.getPlayer(member); if (player != null) player.sendMessage(line); }
    }

    /** Who hears about a sabotage: everyone for the event, or a duel's players and watchers (null means everyone). */
    private static Set<UUID> audience(Scope scope) { return scope.duel() == null ? null : scope.duel().audience(); }

    public void endAll(boolean announce) { for (String id : List.copyOf(active.keySet())) end(id, announce); }

    @Override public void stageStarted(Stage stage) { uses.remove(Scope.EVENT.key()); lastTrigger.remove(Scope.EVENT.key()); }

    @Override public void stageEnded(Stage stage) {
        for (var entry : List.copyOf(active.entrySet())) if (entry.getValue().scope().duel() == null) end(entry.getKey(), true);
        uses.remove(Scope.EVENT.key());
    }

    /**
     * Event sabotages: contestants in the current event (or online contestants between events). Duel sabotages:
     * that duel's players. Hackers only if configured.
     */
    private boolean affected(UUID id, Scope scope) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || plugin.getRoleManager().getRole(id) != PlayerRole.CONTESTANT) return false;
        if (!affectsHackers() && plugin.getHackers().isHacker(id)) return false;
        if (scope.duel() != null) return scope.duel().isPlayer(id) && !scope.duel().isOver();
        Stage stage = plugin.getStages().current();
        return stage == null || stage.involves(id) || !stage.started();
    }

    private void sync(Active running) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean should = affected(id, running.scope()) && !player.isDead();
            if (should && !running.applied().contains(id)) {
                running.applied().add(id);
                safely(running, () -> running.sabotage().apply(player, running.context()));
            } else if (!should && running.applied().contains(id) && !player.isDead()) {
                running.applied().remove(id);
                safely(running, () -> running.sabotage().remove(player, running.context()));
            }
        }
    }

    private void tick() {
        seconds++;
        long now = System.currentTimeMillis();
        for (var entry : List.copyOf(active.entrySet())) {
            Active running = entry.getValue();
            var duel = running.scope().duel();
            // A duel's sabotages end with the duel.
            if (duel != null && (duel.isOver() || !plugin.getDuels().duels().contains(duel))) {
                end(entry.getKey(), false);
                uses.remove(running.scope().key());
                lastTrigger.remove(running.scope().key());
                continue;
            }
            if (running.endsAt() > 0 && now >= running.endsAt()) { end(entry.getKey(), true); continue; }
            sync(running);
            safely(running, () -> running.sabotage().tick(running.context(), seconds));
        }
    }

    private void safely(Active running, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.SEVERE, "Sabotage " + running.sabotage().id() + " failed", failure); }
    }

    // ---- Hooks ---------------------------------------------------------------------------------

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (active.isEmpty() || !(event.getEntity() instanceof Player victim)) return;
        for (Active running : List.copyOf(active.values())) {
            safely(running, () -> running.sabotage().onDamage(event, victim, running.context()));
            if (event instanceof EntityDamageByEntityEvent hit) {
                Player attacker = attacker(hit);
                if (attacker != null) safely(running, () -> running.sabotage().onHit(hit, attacker, victim, running.context()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDamage(EntityDamageByEntityEvent event) {
        if (active.isEmpty() || !(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        if (attacker == null) return;
        for (Active running : List.copyOf(active.values())) {
            safely(running, () -> running.sabotage().afterHit(event, attacker, victim, running.context()));
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { tasks.later(20, () -> active.values().forEach(this::sync)); }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        tasks.later(2, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            for (Active running : active.values()) {
                if (running.applied().contains(id)) safely(running, () -> running.sabotage().apply(player, running.context()));
            }
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        for (Active running : active.values()) {
            if (running.applied().remove(event.getPlayer().getUniqueId())) {
                safely(running, () -> running.sabotage().remove(event.getPlayer(), running.context()));
            }
        }
    }
}
