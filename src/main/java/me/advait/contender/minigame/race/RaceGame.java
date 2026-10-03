package me.advait.contender.minigame.race;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.display.Theme;
import me.advait.contender.duel.HurtRules;
import me.advait.contender.kit.Kit;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.minigame.MinigameType;
import me.advait.contender.tab.StandingsLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Mace Race: racers bounce off checkpoint mobs with Wind Burst maces, hitting them in order.
 * A hit may skip ahead up to the course's checkpoint jump. Landing (if enabled) or falling returns you
 * to your last checkpoint; the bed returns you on demand. Fastest to hit the finish wins.
 */
public final class RaceGame extends Minigame {
    private final RaceCourse course;
    private final Kit kit;
    private final int timeLimitSeconds;
    private final Map<UUID, Integer> progress = new HashMap<>();
    private final Map<UUID, Long> finishTimes = new HashMap<>();
    private final Map<UUID, Integer> mobIndex = new HashMap<>();
    private final List<LivingEntity> mobs = new ArrayList<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private final Map<UUID, Long> bedCooldown = new HashMap<>();
    private final RaceGrounding grounding = new RaceGrounding();
    private long startedAt;
    private double lowest;

    @Override public Kit kit() { return kit; }

    RaceGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, RaceCourse course, Kit kit, int timeLimitSeconds) {
        super(plugin, type, id, name, players);
        this.course = course;
        this.kit = kit;
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public RaceCourse course() { return course; }

    @Override protected int minimumPlayers() { return 1; }

    // ---- Setup ---------------------------------------------------------------------------------

    @Override protected CompletableFuture<Void> prepare() {
        String problem = course.problem();
        if (problem != null) return CompletableFuture.failedFuture(new IllegalStateException(course.name() + ": " + problem));
        if (plugin.getMinigames().raceEditor() != null && plugin.getMinigames().raceEditor().courseBeingEdited(course.id())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Someone is editing " + course.name() + ". Finish editing first."));
        }
        if (kit != null) RaceKit.validate(kit);
        World world = course.world();
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Location> points = new ArrayList<>();
        points.add(course.startLocation());
        for (RaceCourse.Checkpoint point : course.checkpoints()) points.add(point.at(world));
        for (Location point : points) {
            long key = ((long) point.getBlockX() >> 4) << 32 | ((point.getBlockZ() >> 4) & 0xFFFFFFFFL);
            if (seen.add(key)) loads.add(world.getChunkAtAsync(point));
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenRun(() -> {
            // Cancelled while the chunks loaded: cleanup has already run, so leave nothing behind.
            if (state != State.PREPARING) return;
            for (var load : loads) { Chunk chunk = load.join(); if (chunk.addPluginChunkTicket(plugin)) tickets.add(chunk); }
            spawnMobs();
        });
    }

    private void spawnMobs() {
        Theme theme = plugin.getThemes().current();
        World world = course.world();
        List<RaceCourse.Checkpoint> points = course.checkpoints();
        lowest = course.start().y();
        for (int i = 0; i < points.size(); i++) {
            Location at = points.get(i).at(world);
            lowest = Math.min(lowest, at.getY());
            LivingEntity mob = RaceMobs.spawn(at, points.get(i).type(), Component.text(course.label(i), i == points.size() - 1 ? theme.primary() : theme.secondary()), "race", false);
            mobs.add(mob);
            mobIndex.put(mob.getUniqueId(), i + 1);
        }
        lowest -= 40;
    }

    @Override protected void setup(List<Player> players) {
        Location start = course.startLocation();
        for (Player player : players) {
            normalize(player);
            player.teleport(start);
            if (kit == null) RaceKit.give(player); else RaceKit.give(player, kit);
            progress.put(player.getUniqueId(), 0);
        }
        broadcast(Msg.text("Hit the checkpoints in order with your mace. You can skip up to " + course.maxJump() + " at a time. The bed takes you back.", DialogPalette.MUTED));
    }

    private void normalize(Player player) {
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20 : max.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        grounding.reset(player.getUniqueId());
    }

    @Override protected void begin() {
        startedAt = System.nanoTime();
        tasks.repeat(1, 1, this::monitor);
    }

    @Override protected void tick() {
        for (LivingEntity mob : mobs) {
            if (!mob.isValid()) continue;
            mob.setHealth(Math.min(mob.getHealth() + 1024, mob.getAttribute(Attribute.MAX_HEALTH).getValue()));
            mob.setFireTicks(0);
        }
        if (mobs.stream().anyMatch(mob -> !mob.isValid() && mob.getLocation().isChunkLoaded())) respawnMissing();
        long elapsed = elapsed();
        for (Participant participant : roster()) {
            if (participant.status != Status.PLAYING || finishTimes.containsKey(participant.id)) continue;
            participant.value = "#" + progress.getOrDefault(participant.id, 0) + "  " + time(elapsed).replaceFirst("\\.[0-9]{3}$", "");
            Player racer = Bukkit.getPlayer(participant.id);
            if (racer != null) Msg.status(racer, progressBar(progress.getOrDefault(participant.id, 0), elapsed));
        }
        if (timeLimitSeconds > 0 && elapsed >= timeLimitSeconds * 1_000_000_000L) {
            broadcast(Msg.text("Time's up.", DialogPalette.WARNING));
            finish();
        }
    }

    /** A checkpoint mob that vanished (for example a /kill) comes straight back. */
    private void respawnMissing() {
        Theme theme = plugin.getThemes().current();
        List<RaceCourse.Checkpoint> points = course.checkpoints();
        for (int i = 0; i < mobs.size(); i++) {
            if (mobs.get(i).isValid()) continue;
            mobIndex.remove(mobs.get(i).getUniqueId());
            LivingEntity mob = RaceMobs.spawn(points.get(i).at(course.world()), points.get(i).type(),
                    Component.text(course.label(i), i == points.size() - 1 ? theme.primary() : theme.secondary()), "race", false);
            mobs.set(i, mob);
            mobIndex.put(mob.getUniqueId(), i + 1);
        }
    }

    private long elapsed() { return startedAt == 0 ? 0 : System.nanoTime() - startedAt; }

    /** Every tick: keep hunger full and send racers who landed back to their checkpoint. */
    private void monitor() {
        for (Player player : playing()) {
            if (finishTimes.containsKey(player.getUniqueId()) || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) continue;
            player.setFoodLevel(20);
            player.setSaturation(20);
            if (player.getLocation().getY() < lowest) { returnPlayer(player); continue; }
            if (course.returnOnGround()) {
                Location at = player.getLocation();
                if (grounding.landed(player.getUniqueId(), RaceGrounding.supported(at.getWorld(), player.getBoundingBox()))) returnPlayer(player);
            }
        }
    }

    @Override protected void cleanup() {
        mobs.forEach(Entity::remove);
        mobs.clear();
        mobIndex.clear();
        tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
        tickets.clear();
        grounding.clear();
    }

    // ---- Returns -------------------------------------------------------------------------------

    private Location returnPoint(Player player) {
        int reached = progress.getOrDefault(player.getUniqueId(), 0);
        Location point;
        if (reached <= 0) point = course.startLocation();
        else {
            LivingEntity mob = mobs.get(reached - 1);
            point = mob.isValid() ? mob.getLocation() : course.checkpoints().get(reached - 1).at(course.world());
            point = point.clone().add(0, (mob.isValid() ? mob.getHeight() : 2) + course.returnHeight(), 0);
        }
        point.setYaw(player.getLocation().getYaw());
        point.setPitch(player.getLocation().getPitch());
        return point;
    }

    private void returnPlayer(Player player) {
        player.teleport(returnPoint(player));
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.setFireTicks(0);
        grounding.reset(player.getUniqueId());
    }

    // ---- Hits ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Integer index = mobIndex.get(event.getEntity().getUniqueId());
        if (index != null) { checkpointHit(event, index); return; }
        if (!(event.getEntity() instanceof Player player) || plugin.getRegistry().owner(player.getUniqueId()) != this) return;
        if (state != State.RUNNING || finishTimes.containsKey(player.getUniqueId()) || player.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) { event.setCancelled(true); returnPlayer(player); return; }
        // Racers feel hits and knockback but never lose health.
        HurtRules.removeHealthDamage(event);
    }

    private void checkpointHit(EntityDamageEvent event, int index) {
        if (!(event instanceof EntityDamageByEntityEvent hit) || !(hit.getDamager() instanceof Player racer)
                || state != State.RUNNING || !isPlaying(racer.getUniqueId()) || finishTimes.containsKey(racer.getUniqueId())
                || hit.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !RaceKit.isWeapon(racer.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            return;
        }
        int reached = progress.getOrDefault(racer.getUniqueId(), 0);
        if (index > reached + course.maxJump()) {
            event.setCancelled(true);
            int furthest = Math.min(reached + course.maxJump(), course.size()); // 1-based, like index
            String next = course.label(reached), last = course.label(furthest - 1);
            Msg.notice(racer, Msg.text("Too far ahead. Hit " + (furthest == reached + 1 ? next + " first." : next + " to " + last + "."), DialogPalette.WARNING));
            return;
        }
        // A real hit lets the mace's Wind Burst launch the racer; the mob is healed right after.
        event.setDamage(Math.clamp(event.getDamage(), 1, 10));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || !(event.getDamager() instanceof Player racer)) return;
        Integer index = mobIndex.get(mob.getUniqueId());
        if (index == null) return;
        tasks.later(1, () -> { if (mob.isValid()) mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue()); });
        int reached = progress.getOrDefault(racer.getUniqueId(), 0);
        if (index <= reached || finishTimes.containsKey(racer.getUniqueId())) return;
        progress.put(racer.getUniqueId(), index);
        participant(racer.getUniqueId()).score = index;
        if (index == course.size()) { finished(racer); return; }
        plugin.getCelebrations().ring(mob.getLocation().add(0, mob.getHeight() / 2, 0), 1.0, 16);
        Sounds.TICK_HIGH.play(racer, 1f + index / (float) course.size());
        racer.sendActionBar(progressBar(index, elapsed()));
    }

    private void finished(Player racer) {
        long time = elapsed();
        finishTimes.put(racer.getUniqueId(), time);
        Participant participant = participant(racer.getUniqueId());
        participant.place = nextPlace();
        participant.value = time(time);
        participant.score = course.size() + 1_000_000.0 / Math.max(1, time / 1_000_000);
        Theme theme = plugin.getThemes().current();
        Msg.broadcast(Component.text("#" + participant.place + " ", theme.primary()).append(plugin.getNameTagManager().displayName(racer))
                .append(Msg.text(" finished in " + time(time) + ".", DialogPalette.MUTED)));
        plugin.getCelebrations().fireworks(racer.getLocation(), 3);
        Msg.title(racer, Component.text("Finished", theme.primary()), Msg.text(time(time) + "  ·  #" + participant.place, DialogPalette.TEXT), 2, 50, 10);
        Sounds.VICTORY.play(racer);
        racer.sendActionBar(Msg.text("Finished  ·  " + time(time), DialogPalette.ACCENT));
        tasks.later(1, () -> { if (racer.isOnline()) { racer.getInventory().clear(); racer.setGameMode(GameMode.SPECTATOR); } });
        checkEnd();
    }

    @Override protected void checkEnd() {
        if (state != State.RUNNING) return;
        boolean racing = roster().stream().anyMatch(p -> p.status == Status.PLAYING && !finishTimes.containsKey(p.id));
        if (!racing) tasks.later(30, this::finish);
    }

    @Override protected void playerLeft(Participant participant) {
        // The clock keeps running; they return to their checkpoint when they come back.
        broadcast(Msg.text(participant.name + " can rejoin and carry on from their checkpoint.", DialogPalette.MUTED));
    }

    @Override protected void playerReturned(Player player, Participant participant) {
        if (participant.status != Status.PLAYING || finishTimes.containsKey(player.getUniqueId())) { super.playerReturned(player, participant); return; }
        normalize(player);
        // Items can be lost if the server didn't save them; a missing bed means the kit is gone.
        if (Arrays.stream(player.getInventory().getContents()).noneMatch(RaceKit::isReturn)) {
            if (kit == null) RaceKit.give(player); else RaceKit.give(player, kit);
        }
        returnPlayer(player);
    }

    // ---- Bed, items and the course -------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (plugin.getRegistry().owner(player.getUniqueId()) != this) return;
        if (state == State.COUNTDOWN) { event.setCancelled(true); return; }
        if (RaceKit.isReturn(event.getItem())) {
            event.setCancelled(true);
            if (event.getAction().isRightClick() && state == State.RUNNING && !finishTimes.containsKey(player.getUniqueId())) {
                long now = System.nanoTime();
                if (now - bedCooldown.getOrDefault(player.getUniqueId(), 0L) > 500_000_000L) {
                    bedCooldown.put(player.getUniqueId(), now);
                    returnPlayer(player);
                }
            }
        } else if (event.getAction().isRightClick() && event.getClickedBlock() != null) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (state != State.COUNTDOWN || event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) return;
        if (plugin.getRegistry().owner(event.getPlayer().getUniqueId()) != this) return;
        Location held = event.getFrom().clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (plugin.getRegistry().owner(event.getPlayer().getUniqueId()) == this && !contains(event.getTo())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getRegistry().owner(event.getPlayer().getUniqueId()) == this) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && plugin.getRegistry().owner(player.getUniqueId()) == this) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFood(FoodLevelChangeEvent event) {
        if (plugin.getRegistry().owner(event.getEntity().getUniqueId()) == this) { event.setCancelled(true); event.getEntity().setFoodLevel(20); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventory(InventoryClickEvent event) {
        if (plugin.getRegistry().owner(event.getWhoClicked().getUniqueId()) != this) return;
        switch (event.getAction()) {
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT -> event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.getRegistry().owner(event.getPlayer().getUniqueId()) == this) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.getRegistry().owner(event.getPlayer().getUniqueId()) == this) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (plugin.getRegistry().owner(event.getPlayer().getUniqueId()) == this) event.setCancelled(true);
    }

    /** Wind Burst and wind charges keep their push; they never break the course. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) { if (contains(event.getLocation())) event.blockList().clear(); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { if (contains(event.getBlock().getLocation())) event.blockList().clear(); }

    // Checkpoint mobs stay exactly where they are.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobDeath(EntityDeathEvent event) {
        if (!mobIndex.containsKey(event.getEntity().getUniqueId())) return;
        event.setCancelled(true);
        event.setReviveHealth(event.getEntity().getAttribute(Attribute.MAX_HEALTH).getValue());
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onMobMove(io.papermc.paper.event.entity.EntityMoveEvent event) {
        if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onMobTeleport(EntityTeleportEvent event) {
        if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onCombust(EntityCombustEvent event) { if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onTarget(EntityTargetEvent event) { if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onTransform(EntityTransformEvent event) { if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onPrime(ExplosionPrimeEvent event) { if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onMobInteract(PlayerInteractEntityEvent event) { if (mobIndex.containsKey(event.getRightClicked().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onLeash(PlayerLeashEntityEvent event) { if (mobIndex.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.getRegistry().owner(event.getEntity().getUniqueId()) != this) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin.getRegistry().owner(player.getUniqueId()) != this) return;
        event.setRespawnLocation(finishTimes.containsKey(player.getUniqueId()) ? watchSpot() : returnPoint(player));
        tasks.later(1, () -> { if (player.isOnline() && !finishTimes.containsKey(player.getUniqueId())) normalize(player); });
    }

    // ---- Spectating ----------------------------------------------------------------------------

    @Override public Location focus() {
        return course.world() == null || course.size() == 0 ? null : course.checkpoints().getFirst().at(course.world());
    }

    @Override public Location spectatorSpawn() {
        Location start = course.startLocation();
        return start == null ? null : start.clone().add(0, 6, 0);
    }

    @Override public boolean contains(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(course.worldName())) return false;
        double minX = course.start().x(), maxX = minX, minZ = course.start().z(), maxZ = minZ;
        for (RaceCourse.Checkpoint point : course.checkpoints()) {
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        return location.getX() >= minX - 96 && location.getX() <= maxX + 96 && location.getZ() >= minZ - 96 && location.getZ() <= maxZ + 96;
    }

    // ---- Results -------------------------------------------------------------------------------

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN || p.place > 0).toList());
        order.sort(Comparator.comparingInt((Participant p) -> p.place > 0 ? p.place : Integer.MAX_VALUE)
                .thenComparing(Comparator.comparingDouble((Participant p) -> p.score).reversed())
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (Participant p : order) {
            String value = p.place > 0 ? p.value : finished() || p.status == Status.OUT ? "DNF  #" + (int) p.score
                    : state == State.READY ? "Waiting" : state == State.COUNTDOWN ? "Starting" : p.value.isBlank() ? "#0" : p.value;
            rows.add(new StandingsLayout.Row(p.id, p.name, value, p.place > 0, p.place == 0 && finished()));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        section.set("course", course.id());
        section.set("kit", kit == null ? "" : kit.getId());
        section.set("time-limit", timeLimitSeconds);
    }

    /** "Checkpoint 3 of 8  ·  1:24" (the finish is the last checkpoint and isn't counted). */
    private Component progressBar(int reached, long elapsed) {
        int checkpoints = course.size() - 1; // the last one is the finish
        Component left = reached >= checkpoints ? Msg.text("Go for the finish", DialogPalette.ACCENT)
                : Msg.text("Checkpoint ", DialogPalette.MUTED).append(Msg.text(reached + " of " + checkpoints, DialogPalette.ACCENT));
        String clock = time(elapsed).replaceFirst("\\.[0-9]{3}$", "");
        return left.append(Msg.text("  ·  " + clock, DialogPalette.TEXT));
    }

    public static String time(long nanos) {
        long millis = Math.max(0, nanos / 1_000_000);
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }
}
