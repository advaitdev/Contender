package me.advait.contender.manhunt;

import me.advait.contender.Contender;
import me.advait.contender.duel.HurtRules;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.kit.Kit;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

/** One life per contestant. Dead players keep their team and watch until the result is settled. */
final class ManhuntSession extends AbstractGameState {
    private final Contender contender;
    private final ManhuntManager manager;
    private final ManhuntRun run;
    private final ManhuntWorld endWorld;
    private final Kit kit;
    private final Set<UUID> entered = new HashSet<>(), leaving = new HashSet<>();
    private final Map<UUID, Location> spawns = new HashMap<>();
    private final Map<UUID, Boolean> collisions = new HashMap<>();
    private boolean internalTeleport;
    private int countdown;
    private int emptyDragonTicks;

    ManhuntSession(Contender plugin, ManhuntManager manager, ManhuntRun run, ManhuntWorld world, Kit kit) {
        super(plugin); contender = plugin; this.manager = manager; this.run = run; endWorld = world; this.kit = kit; countdown = run.countdownSeconds();
    }
    boolean owns(UUID id) { return entered.contains(id) && !leaving.contains(id); }
    boolean playing(UUID id) { return owns(id) && run.alive(id) && run.state() == ManhuntRun.State.RUNNING; }
    private boolean frozen() { return run.state() == ManhuntRun.State.COUNTDOWN; }
    @Override protected void onEnable() {
        run.countdown(); manager.save();
        int index = 0;
        // Mix teams across the ring instead of putting all runners on adjacent pillars.
        var runners = run.entries().stream().filter(e -> run.alive(e.id()) && e.team() == ManhuntRun.Team.RUNNER).iterator();
        var hunters = run.entries().stream().filter(e -> run.alive(e.id()) && e.team() == ManhuntRun.Team.HUNTER).iterator();
        List<ManhuntRun.Entry> order = new ArrayList<>();
        while (runners.hasNext() || hunters.hasNext()) { if (runners.hasNext()) order.add(runners.next()); if (hunters.hasNext()) order.add(hunters.next()); }
        for (var entry : order) {
            Player player = Objects.requireNonNull(Bukkit.getPlayer(entry.id()));
            Location spawn = endWorld.spawn(index++); spawns.put(entry.id(), spawn); entered.add(entry.id());
            collisions.put(entry.id(), player.isCollidable()); player.setCollidable(false);
            player.setGameMode(GameMode.SURVIVAL); player.setFlying(false); player.setAllowFlight(false);
            player.setVelocity(new Vector()); player.setFallDistance(0); player.setFireTicks(0);
            if (!teleport(player, spawn)) throw new IllegalStateException("The End teleport was blocked for " + entry.name() + ".");
            if (kit.isNoClear()) kit.applyEffectsOnly(player); else kit.apply(player);
            player.sendMessage(Component.text(entry.team() == ManhuntRun.Team.RUNNER ? "You are a runner. Kill the dragon to win." : "You are a hunter. Stop every runner to win.", entry.team() == ManhuntRun.Team.RUNNER ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        manager.refresh(); second();
        runRepeating(() -> guarded(this::second), 20, 20);
        runRepeating(() -> guarded(this::tick), 1, 1);
    }
    private void guarded(Runnable task) { try { task.run(); } catch (RuntimeException failure) { manager.failed(failure); } }
    private void second() {
        if (frozen()) {
            if (entered.stream().anyMatch(id -> owns(id) && Bukkit.getPlayer(id) == null)) { manager.failed(new IllegalStateException("A player disconnected during the countdown.")); return; }
            if (countdown == 0) {
                if (endWorld.world().getEntitiesByClass(EnderDragon.class).stream().noneMatch(e -> e.isValid() && !e.isDead())) throw new IllegalStateException("The End dragon is missing. Prepare a fresh End.");
                run.start(); manager.save(); endWorld.thaw();
                collisions.forEach((id, value) -> { var player = Bukkit.getPlayer(id); if (player != null) player.setCollidable(value); });
                manager.refresh();
            }
            for (UUID id : entered) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && owns(id)) player.sendActionBar(countdown == 0 ? Component.empty() : Component.text("Starting in " + countdown, NamedTextColor.GOLD));
            }
            countdown--;
        }
    }
    private void tick() {
        for (UUID id : entered) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !owns(id) || player.isDead()) continue;
            if (frozen()) { player.setVelocity(new Vector()); player.setFallDistance(0); }
            if (kit.keepsHungerFull() && run.alive(id)) fillHunger(player);
        }
        if (run.state() == ManhuntRun.State.RUNNING) {
            var dragons = endWorld.world().getEntitiesByClass(EnderDragon.class);
            if (dragons.stream().anyMatch(d -> d.getHealth() <= 0 || d.getDeathAnimationTicks() > 0)) { dragonDied(); return; }
            if (dragons.stream().noneMatch(Entity::isValid)) {
                if (++emptyDragonTicks >= 100) manager.failed(new IllegalStateException("The dragon disappeared without dying. Prepare a fresh End."));
            } else emptyDragonTicks = 0;
        }
    }
    private static void fillHunger(Player player) { player.setFoodLevel(20); player.setSaturation(20); player.setExhaustion(0); }
    private boolean teleport(Player player, Location location) {
        internalTeleport = true;
        try { return player.teleport(location); } finally { internalTeleport = false; }
    }
    @Override protected void onDisable() {
        leaving.addAll(entered);
        for (UUID id : entered) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            cleanup("Could not clear Manhunt countdown for " + player.getName(), () -> player.sendActionBar(Component.empty()));
            cleanup("Could not restore Manhunt collision for " + player.getName(), () -> player.setCollidable(collisions.getOrDefault(id, true)));
            if (!player.isDead()) cleanup("Could not restore Manhunt player " + player.getName(), () -> manager.restore(player));
        }
    }
    private void cleanup(String message, Runnable action) {
        try { action.run(); }
        catch (RuntimeException | Error failure) { contender.getLogger().log(java.util.logging.Level.SEVERE, message, failure); }
    }
    void withdraw(UUID id) {
        if (!owns(id)) { run.eliminate(id); manager.save(); manager.refresh(); return; }
        leaving.add(id); run.eliminate(id); manager.save();
        Player player = Bukkit.getPlayer(id);
        if (player != null && !player.isDead()) { player.setCollidable(collisions.getOrDefault(id, true)); manager.restore(player); }
        if (run.terminal()) manager.completed();
        else if (frozen() && (run.alive(ManhuntRun.Team.RUNNER) == 0 || run.alive(ManhuntRun.Team.HUNTER) == 0)) manager.failed(new IllegalStateException("Each team needs a player for the countdown."));
        else manager.refresh();
    }
    private void eliminate(Player player) {
        if (!run.eliminate(player.getUniqueId())) return;
        manager.save();
        if (run.terminal()) manager.completed(); else {
            if (!player.isDead()) spectate(player);
            manager.refresh();
        }
    }
    private void spectate(Player player) {
        if (!owns(player.getUniqueId())) return;
        player.getInventory().clear(); player.setFireTicks(0); player.setFallDistance(0);
        player.setGameMode(GameMode.SPECTATOR); player.setCollidable(collisions.getOrDefault(player.getUniqueId(), true));
    }
    private void dragonDied() { if (run.dragonDied()) manager.completed(); }
    static Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        if (entity instanceof Tameable tame && tame.getOwner() instanceof Player player) return player;
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!endWorld.contains(event.getEntity().getLocation())) return;
        Player source = event instanceof EntityDamageByEntityEvent hit ? attacker(hit.getDamager()) : null;
        if (frozen() || source != null && (!owns(source.getUniqueId()) || !run.alive(source.getUniqueId()))) { event.setCancelled(true); return; }
        if (!(event.getEntity() instanceof Player player)) return;
        if (!playing(player.getUniqueId())) { event.setCancelled(true); return; }
        boolean pvp = source != null;
        if (pvp && run.entry(source.getUniqueId()).team() == run.entry(player.getUniqueId()).team()) { event.setCancelled(true); return; }
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID && (pvp ? kit.isPvpHurt() : kit.isPveHurt())) HurtRules.removeHealthDamage(event);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void dragonDeath(EntityDeathEvent event) { if (event.getEntity() instanceof EnderDragon && endWorld.contains(event.getEntity().getLocation())) dragonDied(); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if (!owns(event.getPlayer().getUniqueId())) return;
        event.deathMessage(null); event.setKeepInventory(true); event.setKeepLevel(true); event.getDrops().clear(); event.setDroppedExp(0);
        if (frozen()) manager.failed(new IllegalStateException("A player died during the countdown.")); else eliminate(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event) {
        if (!owns(event.getPlayer().getUniqueId())) return;
        event.setRespawnLocation(spawns.get(event.getPlayer().getUniqueId()));
        runLater(() -> spectate(event.getPlayer()), 1);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        if (owns(event.getPlayer().getUniqueId())) runLater(() -> {
            if (!teleport(event.getPlayer(), spawns.get(event.getPlayer().getUniqueId()))) { withdraw(event.getPlayer().getUniqueId()); return; }
            spectate(event.getPlayer());
        }, 1);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        if (!owns(event.getPlayer().getUniqueId())) return;
        if (frozen()) { manager.failed(new IllegalStateException("A player disconnected during the countdown.")); return; }
        if (run.eliminate(event.getPlayer().getUniqueId())) { manager.save(); if (run.terminal()) manager.completed(); else manager.refresh(); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (!owns(event.getPlayer().getUniqueId()) || !frozen() || event.getTo() == null) return;
        if (event.hasChangedPosition()) { Location fixed = event.getFrom().clone(); fixed.setYaw(event.getTo().getYaw()); fixed.setPitch(event.getTo().getPitch()); event.setTo(fixed); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (internalTeleport || !owns(event.getPlayer().getUniqueId())) return;
        if (event.getTo() == null || !endWorld.contains(event.getTo()) || frozen()) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockBreak(BlockBreakEvent event) { if (endWorld.contains(event.getBlock().getLocation()) && (!playing(event.getPlayer().getUniqueId()) || !kit.isAllowBlockBreak())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockPlace(BlockPlaceEvent event) { if (endWorld.contains(event.getBlock().getLocation()) && (!playing(event.getPlayer().getUniqueId()) || !kit.isAllowBlockPlace())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent event) { if (endWorld.contains(event.getBlock().getLocation()) && (!playing(event.getPlayer().getUniqueId()) || !kit.isAllowBlockPlace())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent event) { if (endWorld.contains(event.getBlock().getLocation()) && (!playing(event.getPlayer().getUniqueId()) || !kit.isAllowBlockBreak())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) { if (owns(event.getPlayer().getUniqueId()) && (frozen() || !run.alive(event.getPlayer().getUniqueId()))) { event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityInteract(PlayerInteractEntityEvent event) { if (owns(event.getPlayer().getUniqueId()) && (frozen() || !run.alive(event.getPlayer().getUniqueId()))) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) { if (endWorld.contains(event.getEntity().getLocation()) && frozen()) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { if (owns(event.getPlayer().getUniqueId()) && frozen()) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) { if (event.getEntity() instanceof Player p && owns(p.getUniqueId()) && (frozen() || !run.alive(p.getUniqueId()))) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventory(InventoryClickEvent event) { if (owns(event.getWhoClicked().getUniqueId()) && frozen()) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventory(InventoryDragEvent event) { if (owns(event.getWhoClicked().getUniqueId()) && frozen()) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hunger(FoodLevelChangeEvent event) { if (event.getEntity() instanceof Player player && owns(player.getUniqueId()) && (frozen() || kit.keepsHungerFull())) { event.setCancelled(true); fillHunger(player); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void regain(EntityRegainHealthEvent event) { if (event.getEntity() instanceof Player p && owns(p.getUniqueId()) && !kit.isNaturalRegen() && (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void advancement(PlayerAdvancementDoneEvent event) { if (owns(event.getPlayer().getUniqueId())) event.message(null); }
}
