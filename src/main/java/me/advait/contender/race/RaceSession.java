package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.map.ArenaMap;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

/** One shared, leased arena. Listener/task ownership ends before the copy is reset. */
public final class RaceSession extends AbstractGameState {
    private final Contender contender;
    private final RaceManager manager;
    private final RaceRun run;
    private final RaceCourse course;
    private final ArenaLease lease;
    private final Set<Chunk> tickets = new HashSet<>();
    private final Map<UUID, Integer> mobs = new HashMap<>();
    private final Map<Integer, Location> checkpoints = new HashMap<>();
    private final Map<UUID, LivingEntity> targets = new HashMap<>();
    private final Set<UUID> entered = new HashSet<>();
    private final Set<UUID> returning = new HashSet<>();
    private final Map<UUID, Long> bedCooldown = new HashMap<>();
    private final RaceGrounding grounding = new RaceGrounding();
    private boolean internalTeleport;
    private int countdown = 10;

    RaceSession(Contender plugin, RaceManager manager, RaceRun run, RaceCourse course, ArenaLease lease) {
        super(plugin); contender = plugin; this.manager = manager; this.run = run; this.course = course; this.lease = lease;
    }
    public ArenaLease lease() { return lease; }
    public boolean contains(Location location) {
        return location.getWorld() != null && location.getWorld().getName().equals(lease.instance().map().getWorldName())
                && lease.instance().cell().containsColumn(location.getX(), location.getZ());
    }
    public boolean owns(UUID id) { return run.racer(id) != null && !returning.contains(id) && entered.contains(id); }
    public boolean racing(UUID id) { return owns(id) && run.state() == RaceRun.State.RUNNING && !run.racer(id).done(); }
    public boolean prepared() { return run.state() != RaceRun.State.READY; }
    @Override protected void onEnable() {
        RaceManager.loadChunks(contender, lease.instance().map(), tickets, this::isEnabled).whenComplete((ignored, failure) -> {
            if (!isEnabled()) return;
            if (failure != null) { manager.failed(failure); return; }
            try { prepare(); } catch (RuntimeException | Error error) { manager.failed(error); }
        });
    }
    private void prepare() {
        ArenaMap map = lease.instance().map();
        Objects.requireNonNull(Bukkit.getWorld(map.getWorldName())).setPVP(true);
        Map<Integer, LivingEntity> found = RaceManager.scan(map, course);
        int count = found.size() - 1;
        for (var entry : found.entrySet()) {
            int index = entry.getKey() == Integer.MAX_VALUE ? count + 1 : entry.getKey();
            LivingEntity entity = entry.getValue();
            mobs.put(entity.getUniqueId(), index); targets.put(entity.getUniqueId(), entity);
            checkpoints.put(index, entity.getLocation().add(0, course.returnHeight(), 0));
            var health = entity.getAttribute(Attribute.MAX_HEALTH);
            if (health == null) throw new IllegalArgumentException("Checkpoint mobs must have health.");
            health.setBaseValue(1024); entity.setHealth(1024); entity.setInvulnerable(false);
            entity.setMaximumNoDamageTicks(0); entity.setNoDamageTicks(0);
            entity.setAI(false); entity.setGravity(false); entity.setSilent(true); entity.setCollidable(false);
            entity.setRemoveWhenFarAway(false); entity.setPersistent(true); entity.setCustomNameVisible(true);
            entity.setFireTicks(0); entity.getActivePotionEffects().forEach(e -> entity.removePotionEffect(e.getType()));
            var knockback = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE); if (knockback != null) knockback.setBaseValue(1);
            var explosion = entity.getAttribute(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE); if (explosion != null) explosion.setBaseValue(1);
        }
        // Validate the whole roster before changing the first player.
        for (var racer : run.racers()) if (!racer.done()) manager.requireAvailable(racer);
        var kit = manager.selectedKit(run.kitId());
        for (var racer : run.racers()) if (!racer.done()) manager.capture(Objects.requireNonNull(Bukkit.getPlayer(racer.id())));
        run.countdown(count); manager.save();
        for (var racer : run.racers()) {
            if (racer.done()) continue;
            Player player = Objects.requireNonNull(Bukkit.getPlayer(racer.id()));
            entered.add(racer.id());
            if (!teleport(player, map.getTeam1Spawn())) throw new IllegalStateException("The race teleport was blocked for " + racer.name() + ".");
            normalize(player);
            if (kit == null) RaceKit.give(player); else RaceKit.give(player, kit);
        }
        contender.refreshVoiceRouting(); contender.refreshHackerAttributes();
        tick(); runRepeating(() -> {
            try { tick(); } catch (RuntimeException failure) { manager.failed(failure); }
        }, 20, 20);
        runRepeating(this::monitorRacers, 1, 1);
    }
    private void normalize(Player player) {
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setGameMode(GameMode.SURVIVAL); player.setAllowFlight(false); player.setFlying(false);
        player.setHealth(player.getMaxHealth()); fillHunger(player);
        grounding.reset(player.getUniqueId()); player.setFallDistance(0); player.setFireTicks(0); player.setVelocity(new Vector());
    }
    private void tick() {
        if (targets.values().stream().anyMatch(e -> !e.isValid() || e.isDead())) {
            manager.failed(new IllegalStateException("A checkpoint mob disappeared. The race was stopped.")); return;
        }
        targets.values().forEach(e -> { e.setHealth(e.getMaxHealth()); e.setVelocity(new Vector()); e.setFireTicks(0); });
        if (run.state() == RaceRun.State.COUNTDOWN) {
            boolean offline = run.racers().stream().anyMatch(r -> !r.done() && Bukkit.getPlayer(r.id()) == null);
            if (offline) { manager.failed(new IllegalStateException("A racer disconnected during the countdown. Create the race again when everyone is ready.")); return; }
            if (countdown == 0) { run.start(System.nanoTime()); manager.save(); contender.refreshHackerAttributes(); }
            for (UUID id : entered) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && owns(id)) player.sendActionBar(countdown == 0 ? Component.empty() : Component.text("Starting in " + countdown, NamedTextColor.GOLD));
            }
            countdown--;
        }
        if (run.state() == RaceRun.State.RUNNING && run.timeLimitSeconds() > 0
                && run.elapsed(System.nanoTime()) >= run.timeLimitSeconds() * 1_000_000_000L) manager.end(RaceRun.State.FINISHED);
    }
    private static void fillHunger(Player player) {
        player.setFoodLevel(20); player.setSaturation(20); player.setExhaustion(0);
    }
    // Keep the HUD full even if another plugin changes food directly between hunger events.
    void monitorRacers() {
        for (UUID id : entered) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !owns(id) || player.isDead()) continue;
            fillHunger(player);
            if (course.returnOnGround() && racing(id) && player.getGameMode() != GameMode.SPECTATOR) {
                checkGround(player, player.getLocation());
            }
        }
    }
    private void checkGround(Player player, Location location) {
        if (!contains(location)) return;
        var box = player.getBoundingBox();
        Location current = player.getLocation();
        box.shift(location.getX() - current.getX(), location.getY() - current.getY(), location.getZ() - current.getZ());
        if (grounding.landed(player.getUniqueId(), RaceGrounding.supported(location.getWorld(), box))) returnPlayer(player);
    }
    @Override protected void onDisable() {
        contender.refreshHackerAttributes();
        for (var racer : run.racers()) {
            Player player = Bukkit.getPlayer(racer.id());
            if (player != null) {
                try { returning.add(racer.id()); manager.restore(player); }
                catch (RuntimeException error) { contender.getLogger().log(java.util.logging.Level.SEVERE, "Could not restore racer " + racer.name(), error); }
            }
        }
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin);
        tickets.clear(); grounding.clear(); contender.refreshVoiceRouting();
    }
    public void withdraw(UUID id) {
        run.withdraw(id); returning.add(id); grounding.reset(id); manager.save();
        Player player = Bukkit.getPlayer(id); if (player != null) manager.restore(player);
        if (run.allDone()) manager.end(RaceRun.State.FINISHED);
    }
    private Location returnPoint(Player player) {
        var racer = run.racer(player.getUniqueId());
        Location location = checkpoints.getOrDefault(racer.checkpoint(), lease.instance().map().getTeam1Spawn()).clone();
        location.setYaw(player.getYaw()); location.setPitch(player.getPitch()); return location;
    }
    private boolean teleport(Player player, Location location) {
        internalTeleport = true;
        try { return player.teleport(location); } finally { internalTeleport = false; }
    }
    private void returnPlayer(Player player) {
        if (!owns(player.getUniqueId())) return;
        if (teleport(player, returnPoint(player))) { grounding.reset(player.getUniqueId()); player.setFallDistance(0); player.setVelocity(new Vector()); player.setFireTicks(0); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        Integer index = mobs.get(event.getEntity().getUniqueId());
        if (index != null) {
            if (!(event instanceof EntityDamageByEntityEvent hit) || !(hit.getDamager() instanceof Player player)
                    || !racing(player.getUniqueId()) || hit.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || !RaceKit.isWeapon(player.getInventory().getItemInMainHand())) { event.setCancelled(true); return; }
            var racer = run.racer(player.getUniqueId());
            if (index > racer.checkpoint() + run.maxAdvance()) { event.setCancelled(true); return; }
            LivingEntity mob = (LivingEntity) event.getEntity();
            mob.setHealth(mob.getMaxHealth());
            // Keep a real successful hit for Wind Burst's native post-attack effect.
            event.setDamage(Math.min(10, Math.max(1, event.getDamage())));
        } else if (event.getEntity() instanceof Player player) {
            Player attacker = attackingPlayer(event);
            if (!owns(player.getUniqueId())) {
                if (attacker != null && owns(attacker.getUniqueId())) event.setCancelled(true);
                return;
            }
            if (!racing(player.getUniqueId()) || attacker != null && !racing(attacker.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) { event.setCancelled(true); returnPlayer(player); }
            else me.advait.contender.duel.HurtRules.removeHealthDamage(event);
        }
    }
    private static Player attackingPlayer(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent hit) {
            if (hit.getDamager() instanceof Player player) return player;
            if (hit.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        }
        var source = event.getDamageSource();
        return source != null && source.getCausingEntity() instanceof Player player ? player : null;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void scored(EntityDamageByEntityEvent event) {
        Integer checkpoint = mobs.get(event.getEntity().getUniqueId());
        if (checkpoint == null || event.getFinalDamage() <= 0 || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player player) || !racing(player.getUniqueId())
                || !RaceKit.isWeapon(player.getInventory().getItemInMainHand())) return;
        RaceRun.Hit result = run.hit(player.getUniqueId(), checkpoint, System.nanoTime());
        if (result != RaceRun.Hit.CHECKPOINT && result != RaceRun.Hit.FINISH) return;
        try { manager.save(); } catch (RuntimeException failure) { runLater(() -> manager.failed(failure), 1); return; }
        if (result == RaceRun.Hit.FINISH) {
            manager.celebrate(player.getLocation());
            var racer = run.racer(player.getUniqueId());
            Component message = Component.text("#" + racer.place() + " ", NamedTextColor.GOLD)
                    .append(contender.getNameTagManager().displayName(racer.id(), racer.name()))
                    .append(Component.text(" finished in " + RaceRun.time(racer.finishNanos()) + ".", NamedTextColor.GRAY));
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
            runLater(() -> { if (player.isOnline() && owns(racer.id())) { player.setGameMode(GameMode.SPECTATOR); contender.refreshVoiceRouting(); contender.refreshHackerAttributes(); } }, 1);
            if (run.allDone()) runLater(() -> manager.end(RaceRun.State.FINISHED), 20);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        Location destination = event.getTo();
        contain(event);
        if (event.isCancelled() || event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        if (!event.getTo().equals(destination)) { grounding.reset(event.getPlayer().getUniqueId()); return; }
        if (course.returnOnGround() && racing(event.getPlayer().getUniqueId()) && event.getPlayer().getGameMode() != GameMode.SPECTATOR) {
            checkGround(event.getPlayer(), destination);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(PlayerTeleportEvent event) { contain(event); }
    private void contain(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (internalTeleport || !owns(player.getUniqueId()) || event.getTo() == null) return;
        if (run.state() == RaceRun.State.COUNTDOWN && event.hasChangedPosition()) {
            Location held = event.getFrom().clone(); held.setYaw(event.getTo().getYaw()); held.setPitch(event.getTo().getPitch()); event.setTo(held); return;
        }
        if (!contains(event.getTo()) || event.getTo().getY() < lease.instance().map().getBounds().minY() - 5) {
            if (event instanceof PlayerTeleportEvent) event.setCancelled(true);
            else { event.setTo(returnPoint(player)); player.setVelocity(new Vector()); player.setFallDistance(0); }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void bed(PlayerInteractEvent event) {
        Player player = event.getPlayer(); if (!owns(player.getUniqueId())) return;
        if (RaceKit.isReturn(event.getItem())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND && event.getAction().isRightClick() && run.state() == RaceRun.State.RUNNING) {
                long now = System.nanoTime();
                if (now - bedCooldown.getOrDefault(player.getUniqueId(), 0L) > 500_000_000) { bedCooldown.put(player.getUniqueId(), now); returnPlayer(player); }
            }
        } else if (event.getAction().isRightClick() && event.getClickedBlock() != null) {
            // Arena protection blocks block use; consumable items still need their normal right-click.
            event.setUseInteractedBlock(Event.Result.DENY);
            if (run.state() == RaceRun.State.RUNNING) event.setUseItemInHand(Event.Result.ALLOW);
        }
        if (run.state() == RaceRun.State.COUNTDOWN) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void food(FoodLevelChangeEvent e) { if (owns(e.getEntity().getUniqueId())) { e.setCancelled(true); e.getEntity().setFoodLevel(20); e.getEntity().setSaturation(20); e.getEntity().setExhaustion(0); } }
    @EventHandler(priority = EventPriority.HIGHEST) public void exhaustion(EntityExhaustionEvent e) { if (owns(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void target(EntityTargetLivingEntityEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void combust(EntityCombustEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId()) || owns(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e) { if (owns(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void pickup(EntityPickupItemEvent e) { if (owns(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void inventory(InventoryClickEvent e) { if (owns(e.getWhoClicked().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void inventory(InventoryDragEvent e) { if (owns(e.getWhoClicked().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) { if (owns(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void portal(EntityPortalEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void mobTeleport(EntityTeleportEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void transform(EntityTransformEvent e) { if (mobs.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void advancement(PlayerAdvancementDoneEvent e) { if (owns(e.getPlayer().getUniqueId())) e.message(null); }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        if (!owns(e.getPlayer().getUniqueId())) return;
        runLater(() -> {
            Player player = e.getPlayer(); if (!player.isOnline() || !owns(player.getUniqueId())) return;
            returnPlayer(player);
            fillHunger(player);
            player.setGameMode(run.racer(player.getUniqueId()).done() ? GameMode.SPECTATOR : GameMode.SURVIVAL);
            contender.refreshVoiceRouting();
        }, 2);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void death(PlayerDeathEvent e) {
        if (!owns(e.getPlayer().getUniqueId())) return;
        e.setKeepInventory(true); e.getDrops().clear(); e.setDroppedExp(0); e.deathMessage(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e) {
        if (owns(e.getPlayer().getUniqueId())) { e.setRespawnLocation(returnPoint(e.getPlayer())); runLater(() -> normalize(e.getPlayer()), 1); }
    }
}
