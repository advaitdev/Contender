package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.arena.ArenaActivity;
import me.advait.contender.arena.ArenaCopy;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.core.Tasks;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * One match in one arena copy.
 *
 * Phases: SORTING (round one: arrange your inventory) → FIGHTING → ROUND_OVER → RESETTING (arena paste
 * when blocks changed) → COUNTDOWN → FIGHTING … → ENDING (celebration) → CLOSED (everyone back in the lobby).
 * Eliminated players watch the rest of the round in Spectator mode. Nobody actually dies unless a totem
 * is involved; lethal hits are intercepted and turned into eliminations.
 */
public final class Duel implements Activity, ArenaActivity, me.advait.contender.spectate.Spectatable {
    public enum Phase { SORTING, FIGHTING, ROUND_OVER, RESETTING, COUNTDOWN, ENDING, CLOSED }

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final int MAX_DRAWS = 3;

    private final int id = IDS.incrementAndGet();
    private final Contender plugin;
    private final DuelService service;
    private final DuelSettings settings;
    private final ArenaCopy arena;
    private final List<DuelTeam> teams;
    private final String label;
    private final Consumer<DuelResult> onResult;
    private final Tasks tasks;
    private final Map<UUID, DuelTeam> teamOf = new HashMap<>();
    private final Set<UUID> watchers = new LinkedHashSet<>();
    private final Map<UUID, ItemStack[]> loadouts = new HashMap<>();
    private final Set<Long> placedBlocks = new HashSet<>();
    private final Map<DuelTeam, BukkitTask> forfeitTimers = new HashMap<>();
    private final DuelHud hud;
    private Phase phase = Phase.SORTING;
    private int round;
    private int draws;
    private boolean resultReported;
    private DuelResult result;

    Duel(Contender plugin, DuelService service, DuelSettings settings, ArenaCopy arena,
         List<List<UUID>> sides, List<String> names, String label, Consumer<DuelResult> onResult) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        this.arena = arena;
        this.label = label;
        this.onResult = onResult;
        this.tasks = new Tasks(plugin, "Duel #" + id);
        List<DuelTeam> built = new ArrayList<>();
        for (int i = 0; i < sides.size(); i++) {
            String name = names != null && i < names.size() && names.get(i) != null ? names.get(i) : joinNames(sides.get(i));
            DuelTeam team = new DuelTeam(i + 1, name, sides.get(i));
            built.add(team);
            for (UUID player : team.players()) teamOf.put(player, team);
        }
        teams = List.copyOf(built);
        ArenaMap layout = arena.layout();
        hud = new DuelHud(plugin, layout.getTeam1Spawn(), layout.getTeam2Spawn());
    }

    private static String joinNames(List<UUID> players) {
        List<String> names = new ArrayList<>();
        for (UUID id : players) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            names.add(name == null ? "Player" : name);
        }
        return String.join(", ", names);
    }

    // ---- Accessors -----------------------------------------------------------------------------

    public int id() { return id; }
    public String label() { return label; }
    @Override public String displayName() { return label == null ? "Duel #" + id : label; }
    public DuelSettings settings() { return settings; }
    public Kit kit() { return settings.kit(); }
    public ArenaCopy arena() { return arena; }
    public ArenaMap layout() { return arena.layout(); }
    public Phase phase() { return phase; }
    public int round() { return round; }
    public List<DuelTeam> teams() { return teams; }
    public DuelTeam team(UUID player) { return teamOf.get(player); }
    public boolean isPlayer(UUID player) { return teamOf.containsKey(player); }
    public boolean isAlive(UUID player) { DuelTeam team = teamOf.get(player); return team != null && team.isAlive(player); }
    public boolean isOver() { return phase == Phase.ENDING || phase == Phase.CLOSED; }
    @Override public Set<UUID> players() { return Collections.unmodifiableSet(teamOf.keySet()); }
    public Set<UUID> watchers() { return Collections.unmodifiableSet(watchers); }
    public DuelResult result() { return result; }

    /** Everyone who should see this match's messages: players and spectators. */
    public Set<UUID> audience() {
        Set<UUID> audience = new LinkedHashSet<>(teamOf.keySet());
        audience.addAll(watchers);
        return audience;
    }

    @Override public Location spectatorSpawn() {
        Location spawn = layout().getSpectatorSpawn();
        return spawn == null ? layout().getTeam1Spawn() : spawn;
    }

    // ---- Start ---------------------------------------------------------------------------------

    /** Claims, snapshots and moves every player. Undoes everything and throws if any step fails. */
    void start() {
        ActivityRegistry registry = plugin.getRegistry();
        List<UUID> claimed = new ArrayList<>();
        try {
            for (UUID player : teamOf.keySet()) {
                Player online = Bukkit.getPlayer(player);
                if (online == null) throw new IllegalStateException(nameOf(player) + " is offline.");
                if (online.isDead()) throw new IllegalStateException(online.getName() + " must respawn first.");
                registry.claim(player, this, ActivityRegistry.Involvement.PLAYING);
                claimed.add(player);
            }
            for (UUID player : teamOf.keySet()) plugin.getSnapshots().capture(Bukkit.getPlayer(player));
            round = 1;
            Map<UUID, Location> spawns = spawns();
            for (var entry : spawns.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (!teleport(player, entry.getValue())) throw new IllegalStateException("Could not move " + player.getName() + " into the arena.");
            }
        } catch (RuntimeException failure) {
            for (UUID player : claimed) {
                registry.release(player, this);
                Player online = Bukkit.getPlayer(player);
                if (online != null && plugin.getSnapshots().has(player)) plugin.getSnapshots().restoreToLobby(online);
            }
            tasks.close();
            throw failure;
        }
        for (DuelTeam team : teams) team.revive(online(team.players()));
        for (UUID player : teamOf.keySet()) equip(Bukkit.getPlayer(player), true);
        plugin.getHackers().refresh();
        Component vs = Component.empty();
        for (int i = 0; i < teams.size(); i++) {
            if (i > 0) vs = vs.append(Msg.text(" vs ", DialogPalette.MUTED));
            vs = vs.append(Msg.text(teams.get(i).name(), DialogPalette.ACCENT));
        }
        Msg.send(audience(), vs.append(Msg.text("  ·  " + arena.layout().getDisplayName()
                + "  ·  first to " + settings.winsNeeded(), DialogPalette.MUTED)));
        hud.versus(teams, settings.winsNeeded());
        phase = Phase.SORTING;
        countdown(settings.sortSeconds(), true, this::beginFight);
    }

    private Map<UUID, Location> spawns() {
        Map<UUID, Location> spawns = new LinkedHashMap<>();
        ArenaMap layout = layout();
        if (settings.freeForAll()) {
            List<UUID> order = new ArrayList<>(teamOf.keySet());
            Collections.shuffle(order);
            List<Location> ring = SpawnRing.around(layout.getTeam1Spawn(), layout.getTeam2Spawn(), order.size());
            for (int i = 0; i < order.size(); i++) spawns.put(order.get(i), ring.get(i));
        } else {
            for (DuelTeam team : teams) {
                Location spawn = team.number() == 1 ? layout.getTeam1Spawn() : layout.getTeam2Spawn();
                for (UUID player : team.players()) spawns.put(player, spawn);
            }
        }
        return spawns;
    }

    /** Full health, the kit (or the saved hotbar arrangement), and no leftover effects. */
    private void equip(Player player, boolean firstRound) {
        if (player == null) return;
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.closeInventory();
        Kit kit = settings.kit();
        if (kit.isNoClear()) {
            if (!firstRound) restoreLoadout(player);
        } else if (firstRound || !restoreLoadout(player)) {
            kit.apply(player);
        }
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        heal(player);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
        player.setLevel(0);
        player.setExp(0);
        player.setVelocity(new Vector());
    }

    private boolean restoreLoadout(Player player) {
        ItemStack[] saved = loadouts.get(player.getUniqueId());
        if (saved == null) return false;
        ItemStack[] copy = new ItemStack[saved.length];
        for (int i = 0; i < saved.length; i++) copy[i] = saved[i] == null ? null : saved[i].clone();
        player.getInventory().setContents(copy);
        return true;
    }

    static void heal(Player player) {
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20 : max.getValue());
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
    }

    // ---- Countdowns and rounds -----------------------------------------------------------------

    private void countdown(int seconds, boolean sorting, Runnable then) {
        int[] remaining = {seconds};
        BukkitTask[] task = new BukkitTask[1];
        Runnable step = () -> {
            int left = remaining[0]--;
            if (left <= 0) {
                tasks.cancel(task[0]);
                then.run();
                return;
            }
            Set<UUID> audience = audience();
            if (left <= 3) {
                Msg.title(audience, Msg.text(Integer.toString(left), plugin.getThemes().current().primary()), Component.empty(), 0, 22, 2);
                Sounds.TICK_HIGH.play(audience);
            } else {
                Component bar = sorting ? Msg.text("Arrange your inventory  ", DialogPalette.MUTED).append(Msg.text(left + "s", DialogPalette.ACCENT))
                        : Msg.text("Next round in " + left, DialogPalette.MUTED);
                Msg.actionBar(audience, bar);
                if (left <= 5) Sounds.TICK.play(audience);
            }
        };
        task[0] = tasks.repeat(0, 20, step);
    }

    private void beginFight() {
        if (isOver()) return;
        List<DuelTeam> absent = teams.stream().filter(team -> online(team.players()).isEmpty()).toList();
        if (!absent.isEmpty()) {
            // Hold the countdown until the side returns; its forfeit timer ends the match if it doesn't.
            Msg.actionBar(audience(), Msg.text("Waiting for " + absent.getFirst().name() + " to reconnect", DialogPalette.WARNING));
            tasks.later(20, this::beginFight);
            return;
        }
        if (round == 1) {
            for (UUID player : teamOf.keySet()) {
                Player online = Bukkit.getPlayer(player);
                if (online != null) {
                    online.closeInventory();
                    // Inventory items are live views; copy each one so later rounds start with the full loadout.
                    loadouts.put(player, java.util.Arrays.stream(online.getInventory().getContents())
                            .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new));
                }
            }
        }
        hud.hide();
        phase = Phase.FIGHTING;
        Set<UUID> audience = audience();
        Msg.actionBar(audience, Component.empty());
        Msg.title(audience, Msg.text("Fight!", plugin.getThemes().current().primary()), Component.empty(), 0, 16, 6);
        Sounds.GO.play(audience);
        checkRound();
    }

    /** Ends the round when at most one side has players standing. */
    private void checkRound() {
        if (phase != Phase.FIGHTING) return;
        List<DuelTeam> standing = teams.stream().filter(DuelTeam::anyAlive).toList();
        if (standing.size() <= 1) roundOver(standing.isEmpty() ? null : standing.getFirst());
    }

    private void roundOver(DuelTeam winner) {
        phase = Phase.ROUND_OVER;
        if (winner == null) draws++;
        else winner.addPoint();
        Set<UUID> audience = audience();
        var theme = plugin.getThemes().current();
        Component scoreLine = scoreLine();
        if (winner == null) {
            Msg.title(audience, Msg.text("Draw", theme.secondary()), scoreLine, 2, 36, 8);
        } else {
            Msg.title(audience, Msg.text(winner.name(), theme.primary()), Msg.text("wins the round  ", DialogPalette.MUTED).append(scoreLine), 2, 36, 8);
            for (UUID player : winner.players()) { Player online = Bukkit.getPlayer(player); if (online != null) Sounds.ROUND_WIN.play(online); }
        }
        hud.score(teams, winner);
        DuelTeam champion = winner != null && winner.score() >= settings.winsNeeded() ? winner : null;
        if (champion != null) { tasks.later(30, () -> finish(champion, DuelResult.Reason.FINISHED)); return; }
        if (draws > MAX_DRAWS) {
            DuelTeam leader = leader();
            tasks.later(30, () -> finish(leader, DuelResult.Reason.FINISHED));
            return;
        }
        tasks.later(50, this::resetRound);
    }

    private DuelTeam leader() {
        DuelTeam best = null;
        boolean tied = false;
        for (DuelTeam team : teams) {
            if (best == null || team.score() > best.score()) { best = team; tied = false; }
            else if (team.score() == best.score()) tied = true;
        }
        return tied ? null : best;
    }

    private Component scoreLine() {
        var theme = plugin.getThemes().current();
        Component line = Component.empty();
        for (int i = 0; i < teams.size(); i++) {
            if (i > 0) line = line.append(Msg.text(" - ", DialogPalette.MUTED));
            line = line.append(Component.text(teams.get(i).score(), theme.secondary()));
        }
        return line;
    }

    private void resetRound() {
        if (isOver()) return;
        phase = Phase.RESETTING;
        // Everyone floats safely in Spectator mode while the arena is restored underneath them.
        for (UUID player : teamOf.keySet()) {
            Player online = Bukkit.getPlayer(player);
            if (online != null && !online.isDead()) online.setGameMode(GameMode.SPECTATOR);
        }
        attemptReset(0);
    }

    private void attemptReset(int attempt) {
        plugin.getArenas().resetForRound(arena).whenComplete((ignored, failure) -> {
            if (phase != Phase.RESETTING) return;
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, displayName() + ": arena reset failed (attempt " + (attempt + 1) + "): " + Msg.reason(failure));
                if (attempt < 2) { tasks.later(60, () -> attemptReset(attempt + 1)); return; }
                Msg.send(audience(), Msg.text("The arena could not be fully reset. Playing on.", DialogPalette.WARNING));
            }
            startRound();
        });
    }

    private void startRound() {
        if (isOver()) return;
        if (teams.stream().anyMatch(team -> online(team.players()).isEmpty())) {
            // A side is entirely offline; its forfeit timer decides. Check again shortly.
            tasks.later(20, this::startRound);
            return;
        }
        round++;
        Map<UUID, Location> spawns = spawns();
        for (var entry : spawns.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.isDead()) continue;
            if (!teleport(player, entry.getValue())) {
                plugin.getLogger().warning(displayName() + ": could not move " + player.getName() + " to their spawn; retrying.");
                tasks.later(20, this::retryStart);
                round--;
                return;
            }
            equip(player, false);
        }
        for (DuelTeam team : teams) team.revive(online(team.players()).stream().filter(id -> !Bukkit.getPlayer(id).isDead()).toList());
        plugin.getArenas().clearEntities(arena);
        phase = Phase.COUNTDOWN;
        countdown(3, false, this::beginFight);
    }

    private void retryStart() { if (phase == Phase.RESETTING) startRound(); }

    // ---- Eliminations --------------------------------------------------------------------------

    /** Removes a player from the current round. Safe to call more than once. */
    void eliminate(Player victim, Player killer) {
        DuelTeam team = teamOf.get(victim.getUniqueId());
        if (team == null || phase != Phase.FIGHTING || !team.down(victim.getUniqueId())) return;
        plugin.getDeathEffect().play(victim);
        Location at = victim.getLocation();
        victim.getInventory().clear();
        victim.setGameMode(GameMode.SPECTATOR);
        heal(victim);
        victim.setFireTicks(0);
        if (at.getY() < layout().getBounds().minY() || !arena.contains(at)) teleport(victim, spectatorSpawn());
        else victim.setVelocity(new Vector(0, 0.4, 0));
        var theme = plugin.getThemes().current();
        Component message = killer != null && !killer.equals(victim)
                ? plugin.getNameTagManager().displayName(victim).append(Msg.text(" was eliminated by ", DialogPalette.MUTED))
                        .append(plugin.getNameTagManager().displayName(killer))
                : plugin.getNameTagManager().displayName(victim).append(Msg.text(" was eliminated", DialogPalette.MUTED));
        Msg.send(audience(), message);
        Sounds.ELIMINATED.play(audience());
        Msg.title(victim, Msg.text("Eliminated", DialogPalette.DANGER), Msg.text("Watch the rest of the round", DialogPalette.MUTED), 2, 30, 8);
        plugin.getCelebrations().burst(at.clone().add(0, 1, 0));
        if (killer != null && theme != null) Sounds.POP.play(killer);
        checkRound();
    }

    // ---- Finish --------------------------------------------------------------------------------

    private void finish(DuelTeam winner, DuelResult.Reason reason) {
        if (isOver()) return;
        phase = Phase.ENDING;
        tasks.cancelAll();
        forfeitTimers.values().forEach(BukkitTask::cancel);
        forfeitTimers.clear();
        report(resultFor(winner, reason));
        hud.remove();
        Set<UUID> audience = audience();
        var theme = plugin.getThemes().current();
        Msg.actionBar(audience, Component.empty());
        if (winner == null) {
            Msg.title(audience, Msg.text("Draw", theme.secondary()), scoreLine(), 4, 60, 10);
            Msg.send(audience, Msg.text("The match ended in a draw.", DialogPalette.MUTED));
        } else {
            Msg.title(audience, Msg.text(winner.name(), theme.primary()),
                    Msg.text(reason == DuelResult.Reason.FORFEIT ? "wins by forfeit" : "wins the match  ", DialogPalette.MUTED)
                            .append(reason == DuelResult.Reason.FORFEIT ? Component.empty() : scoreLine()), 4, 60, 10);
            Msg.send(audience, Msg.text(winner.name(), DialogPalette.ACCENT).append(Msg.text(
                    reason == DuelResult.Reason.FORFEIT ? " wins by forfeit." : " wins the match ", DialogPalette.TEXT))
                    .append(reason == DuelResult.Reason.FORFEIT ? Component.empty() : scoreLine()));
            for (UUID player : teamOf.keySet()) {
                Player online = Bukkit.getPlayer(player);
                if (online == null) continue;
                if (winner.has(player)) {
                    Sounds.VICTORY.play(online);
                    if (!online.isDead() && online.getGameMode() != GameMode.SPECTATOR) plugin.getCelebrations().fireworks(online.getLocation(), 3);
                } else Sounds.ROUND_LOSS.play(online);
            }
        }
        for (UUID player : teamOf.keySet()) {
            Player online = Bukkit.getPlayer(player);
            if (online != null && online.getGameMode() != GameMode.SPECTATOR) heal(online);
        }
        tasks.later(80, this::close);
    }

    private DuelResult resultFor(DuelTeam winner, DuelResult.Reason reason) {
        int first = teams.get(0).score(), second = teams.size() > 1 ? teams.get(1).score() : 0;
        Integer side = winner == null || teams.size() != 2 ? null : winner.number();
        if (reason == DuelResult.Reason.FORFEIT && side == null) reason = DuelResult.Reason.FINISHED;
        return new DuelResult(reason, first, second, side);
    }

    private void report(DuelResult outcome) {
        result = outcome;
        if (resultReported) return;
        resultReported = true;
        if (onResult == null) return;
        try { onResult.accept(outcome); }
        catch (RuntimeException failure) { plugin.getLogger().log(Level.SEVERE, displayName() + ": could not record the result " + outcome, failure); }
    }

    /** Ends the match now and records the current score, as /endduel does. */
    public void endNow() {
        if (isOver()) return;
        finish(leader(), DuelResult.Reason.FINISHED);
    }

    /** Returns everyone to the lobby and frees the arena. */
    private void close() {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        tasks.close();
        forfeitTimers.values().forEach(BukkitTask::cancel);
        forfeitTimers.clear();
        hud.remove();
        for (UUID watcher : List.copyOf(watchers)) plugin.getSpectate().stop(watcher, true);
        watchers.clear();
        for (UUID player : teamOf.keySet()) {
            plugin.getRegistry().release(player, this);
            Player online = Bukkit.getPlayer(player);
            if (online == null) continue;
            if (online.isDead()) continue; // Restored on respawn or rejoin from the saved snapshot.
            online.sendActionBar(Component.empty());
            plugin.getSnapshots().restoreToLobby(online);
        }
        plugin.getArenas().release(arena);
        service.closed(this);
        plugin.getHackers().refresh();
    }

    @Override public void forceStop(String reason) {
        if (phase == Phase.CLOSED) return;
        if (!resultReported) report(DuelResult.cancelled(teams.get(0).score(), teams.size() > 1 ? teams.get(1).score() : 0));
        if (reason != null && !reason.isBlank()) Msg.send(audience(), Msg.text(reason, DialogPalette.WARNING));
        close();
    }

    // ---- Disconnects ---------------------------------------------------------------------------

    @Override public void handleQuit(Player player) {
        DuelTeam team = teamOf.get(player.getUniqueId());
        if (team == null || isOver()) return;
        Msg.send(audience(), plugin.getNameTagManager().displayName(player).append(Msg.text(" left the match.", DialogPalette.WARNING)));
        if (phase == Phase.FIGHTING) { team.down(player.getUniqueId()); checkRound(); }
        else team.down(player.getUniqueId());
        if (online(team.players()).stream().noneMatch(id -> !id.equals(player.getUniqueId())) && !forfeitTimers.containsKey(team)) {
            int seconds = settings.reconnectSeconds();
            if (seconds == 0) { tasks.later(1, () -> forfeit(team)); return; }
            Msg.send(audience(), Msg.text(team.name() + " has " + seconds + " seconds to return before forfeiting.", DialogPalette.MUTED));
            forfeitTimers.put(team, tasks.later(seconds * 20L, () -> forfeit(team)));
        }
    }

    private void forfeit(DuelTeam leaving) {
        forfeitTimers.remove(leaving);
        if (isOver() || !online(leaving.players()).isEmpty()) return;
        List<DuelTeam> others = teams.stream().filter(team -> team != leaving).toList();
        if (others.size() == 1) finish(others.getFirst(), DuelResult.Reason.FORFEIT);
        else {
            List<DuelTeam> present = others.stream().filter(team -> !online(team.players()).isEmpty()).toList();
            if (present.size() <= 1) finish(present.isEmpty() ? null : present.getFirst(), DuelResult.Reason.FORFEIT);
        }
    }

    @Override public void handleJoin(Player player) {
        DuelTeam team = teamOf.get(player.getUniqueId());
        if (team == null) return;
        BukkitTask timer = forfeitTimers.remove(team);
        if (timer != null) {
            timer.cancel();
            Msg.send(audience(), plugin.getNameTagManager().displayName(player).append(Msg.text(" is back.", DialogPalette.SUCCESS)));
        }
        if (isOver()) {
            plugin.getRegistry().release(player.getUniqueId(), this);
            plugin.getSnapshots().restoreToLobby(player);
            return;
        }
        switch (phase) {
            case SORTING, COUNTDOWN -> {
                Location spawn = spawns().get(player.getUniqueId());
                if (spawn != null && teleport(player, spawn)) {
                    equip(player, phase == Phase.SORTING);
                    team.revive(concat(team.alive(), player.getUniqueId()));
                }
            }
            default -> {
                // Watch until the next round starts.
                player.setGameMode(GameMode.SPECTATOR);
                teleport(player, spectatorSpawn());
            }
        }
    }

    private static List<UUID> concat(Set<UUID> alive, UUID extra) {
        List<UUID> list = new ArrayList<>(alive);
        if (!list.contains(extra)) list.add(extra);
        return list;
    }

    // ---- Watchers ------------------------------------------------------------------------------

    @Override public boolean acceptsWatchers() { return !isOver(); }
    @Override public void addWatcher(UUID player) { watchers.add(player); }
    @Override public void removeWatcher(UUID player) { watchers.remove(player); }
    @Override public boolean contains(Location location) { return arena.contains(location); }
    @Override public boolean hidesAvatars() { return settings.kit().isSpectatorInvisible(); }

    // ---- Rules used by the listener ------------------------------------------------------------

    boolean frozen() { return phase == Phase.SORTING || phase == Phase.COUNTDOWN; }

    @Override public boolean allowBlockChange(Player player, Location at, boolean placing) {
        if (phase != Phase.FIGHTING || !isAlive(player.getUniqueId())) return false;
        long key = blockKey(at);
        if (placing) {
            if (!settings.kit().isAllowBlockPlace()) return false;
            placedBlocks.add(key);
            return true;
        }
        if (!settings.kit().isAllowBlockBreak() || !placedBlocks.contains(key)) return false;
        placedBlocks.remove(key);
        return true;
    }

    @Override public boolean environmentActive() { return phase == Phase.FIGHTING; }

    void clearPlacedBlocks() { placedBlocks.clear(); }

    private static long blockKey(Location at) {
        return ((long) at.getBlockX() & 0x3FFFFFF) << 38 | ((long) at.getBlockZ() & 0x3FFFFFF) << 12 | (at.getBlockY() & 0xFFF);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    boolean teleport(Player player, Location destination) {
        if (player == null || destination == null || destination.getWorld() == null) return false;
        if (player.isInsideVehicle()) player.leaveVehicle();
        service.allowTeleport(player.getUniqueId());
        try {
            if (!player.teleport(destination)) return false;
        } finally { service.endTeleport(player.getUniqueId()); }
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        return true;
    }

    static List<UUID> online(Collection<UUID> players) {
        List<UUID> online = new ArrayList<>();
        for (UUID id : players) if (Bukkit.getPlayer(id) != null) online.add(id);
        return online;
    }

    private static String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "A player" : name;
    }
}
