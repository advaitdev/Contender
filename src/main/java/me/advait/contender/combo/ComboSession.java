package me.advait.contender.combo;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.duel.HurtRules;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.minigame.MinigameArenas;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import java.util.*;

/** A single mannequin and arena serve every turn. Players' normal inventories remain in the return store. */
final class ComboSession extends AbstractGameState {
    private final Contender contender;
    private final ComboManager manager;
    private final ComboRun run;
    private final ArenaLease lease;
    private final ComboAttackTiming attacks;
    private final Set<Chunk> tickets = new HashSet<>();
    private final Set<UUID> entered = new HashSet<>(), watchers = new HashSet<>(), templateEntities = new HashSet<>();
    private final Set<UUID> spectatorOptOuts = new HashSet<>();
    private Mannequin bot;
    private MannequinMotion motion;
    private UUID fighter;
    private int tick, nextTurn, lastAcceptedHit = -1;
    private boolean live, resettingTurn, internalTeleport, cleared;
    ComboSession(Contender plugin, ComboManager manager, ComboRun run, ArenaLease lease) {
        super(plugin); contender = plugin; this.manager = manager; this.run = run; this.lease = lease;
        attacks = new ComboAttackTiming(run.difficulty());
    }
    ArenaLease lease() { return lease; }
    boolean contains(Location location) {
        return location.getWorld() != null && location.getWorld().getName().equals(lease.instance().map().getWorldName())
                && lease.instance().cell().containsColumn(location.getX(), location.getZ());
    }
    boolean owns(UUID id) { return entered.contains(id); }
    boolean watching(UUID id) { return watchers.contains(id); }
    boolean playing(UUID id) { return Objects.equals(fighter, id) && owns(id) && !resettingTurn; }
    @Override protected void onEnable() {
        MinigameArenas.loadChunks(contender, lease.instance().map(), tickets, this::isEnabled).whenComplete((ignored, error) -> {
            if (!isEnabled()) return;
            if (error != null) { manager.failed(error); return; }
            try {
                World world = Bukkit.getWorld(lease.instance().map().getWorldName());
                if (world != null) for (Entity entity : world.getEntities()) if (!(entity instanceof Player) && contains(entity.getLocation())) templateEntities.add(entity.getUniqueId());
                run.start(); manager.save();
                runRepeating(() -> { try { tick(); } catch (RuntimeException failure) { manager.failed(failure); } }, 1, 1);
            } catch (RuntimeException failure) { manager.failed(failure); }
        });
    }
    @Override protected void onDisable() {
        List<Throwable> failures = new ArrayList<>();
        clean(this::removeBot, failures);
        clean(this::clearTurnEntities, failures);
        for (UUID id : List.copyOf(entered)) {
            clean(() -> returnPlayer(id), failures);
        }
        entered.clear(); watchers.clear(); spectatorOptOuts.clear(); fighter = null; live = false;
        for (Chunk chunk : List.copyOf(tickets)) clean(() -> chunk.removePluginChunkTicket(contender), failures);
        tickets.clear();
        for (Player player : Bukkit.getOnlinePlayers()) clean(() -> player.sendActionBar(Component.empty()), failures);
        clean(contender::refreshVoiceRouting, failures);
        clean(contender::refreshHackerAttributes, failures);
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Could not finish Combo cleanup", failures.getFirst());
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }
    private static void clean(Runnable action, List<Throwable> failures) {
        try { action.run(); } catch (RuntimeException | Error failure) { failures.add(failure); }
    }
    private void tick() {
        tick++;
        // Also picks up late arrivals after their pending inventory return has finished.
        if (tick == 1 || tick % 20 == 0) watchRoster();
        if (tick % 5 == 0) Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(Component.text("Combo: " + (cleared ? "∞" : run.hits()), NamedTextColor.GREEN)));
        if (tick % 20 == 0) manager.refresh();
        if (fighter == null) {
            if (tick < nextTurn) return;
            if (run.allDone()) { manager.end(ComboRun.State.FINISHED); return; }
            for (var entry : run.entries()) if (!entry.done() && manager.available(entry)) { begin(entry); return; }
            return;
        }
        Player player = Bukkit.getPlayer(fighter);
        if (player == null) { disconnected(fighter); return; }
        full(player);
        if (bot == null || !bot.isValid() || bot.isDead()) throw new IllegalStateException("The Combo mannequin disappeared.");
        bot.setFireTicks(0); bot.setHealth(bot.getMaxHealth());
        if (live && !resettingTurn && bot.getLocation().getY() < lease.instance().map().getBounds().minY() - 3) {
            if (run.clear()) { cleared = true; scored(); } else retry();
            return;
        }
        if (resettingTurn || !live) { motion.drive(bot, player, run.difficulty(), false); return; }
        if (!contains(bot.getLocation())) { retry(); return; }
        double distance = player.getLocation().distanceSquared(bot.getLocation());
        boolean reacting = attacks.reacting(tick);
        motion.drive(bot, player, run.difficulty(), reacting && distance > 1.4);
        if (attacks.shouldSwing(tick, withinReach(player) && bot.hasLineOfSight(player))) {
            bot.swingMainHand();
            // Mannequins have no native attack implementation. Sourced melee damage runs Paper's
            // ordinary hurt, shield and knockback handling, with the sword's full-charge cadence above.
            player.damage(7, bot);
        }
    }
    private void watchRoster() {
        for (var entry : run.entries()) {
            if (entry.withdrawn() || owns(entry.id()) || spectatorOptOuts.contains(entry.id()) || !manager.available(entry)) continue;
            Player player = Bukkit.getPlayer(entry.id());
            if (player != null) spectate(player);
        }
    }
    private boolean withinReach(Player player) {
        var eye = bot.getEyeLocation();
        return player.getBoundingBox().rayTrace(eye.toVector(), eye.getDirection(), run.difficulty().reach()) != null;
    }
    private void begin(ComboRun.Entry entry) {
        Player player = Objects.requireNonNull(Bukkit.getPlayer(entry.id()));
        if (!owns(entry.id())) manager.capture(player);
        entered.add(entry.id()); watchers.remove(entry.id()); spectatorOptOuts.remove(entry.id()); fighter = entry.id();
        run.begin(entry.id()); manager.save(); cleared = false;
        resetPositions(player); contender.refreshVoiceRouting(); contender.refreshHackerAttributes(); manager.refresh();
    }
    private void resetPositions(Player player) {
        removeBot(); clearTurnEntities(); live = false; resettingTurn = false; lastAcceptedHit = -1; attacks.reset();
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        if (!teleport(player, lease.instance().map().getTeam1Spawn())) throw new IllegalStateException("The Combo teleport was blocked for " + player.getName() + ".");
        player.setGameMode(GameMode.SURVIVAL); player.setAllowFlight(false); player.setFlying(false);
        var kit = manager.requireSwordKit(run.kitId());
        kit.apply(player); full(player); player.setNoDamageTicks(0); player.setFallDistance(0); player.setVelocity(new Vector());
        Location spawn = lease.instance().map().getTeam2Spawn();
        bot = spawn.getWorld().spawn(spawn, Mannequin.class, entity -> {
            entity.setPersistent(false); entity.setRemoveWhenFarAway(false); entity.setImmovable(false); entity.setDescription(null);
            entity.customName(Component.text("Combo", NamedTextColor.WHITE)); entity.setCustomNameVisible(false);
            entity.setGravity(true); entity.setInvulnerable(false); entity.setCollidable(false); ComboAppearance.apply(entity);
            Objects.requireNonNull(entity.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(1024); entity.setHealth(1024);
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            entity.getEquipment().setArmorContents(Arrays.stream(kit.getArmor()).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
            var resistance = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE); if (resistance != null) resistance.setBaseValue(0);
        });
        motion = new MannequinMotion(bot);
    }
    private static void full(Player player) { player.setFoodLevel(20); player.setSaturation(20); player.setExhaustion(0); player.setFireTicks(0); }
    private boolean teleport(Player player, Location destination) {
        internalTeleport = true;
        try { return player.teleport(destination); } finally { internalTeleport = false; }
    }
    private void clearTurnEntities() {
        World world = Bukkit.getWorld(lease.instance().map().getWorldName());
        if (world != null) for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player) && contains(entity.getLocation()) && !templateEntities.contains(entity.getUniqueId())) entity.remove();
        }
    }
    private void removeBot() {
        Mannequin old = bot; bot = null; motion = null;
        if (old != null) old.remove();
    }
    private void retry() {
        if (resettingTurn || fighter == null) return;
        resettingTurn = true; run.retry();
        UUID expected = fighter;
        runLater(() -> { if (!Objects.equals(expected, fighter)) return; Player player = Bukkit.getPlayer(expected); if (player != null) resetPositions(player); }, 1);
    }
    private void scored() {
        if (resettingTurn) return;
        resettingTurn = true; manager.save(); manager.refresh();
        UUID expected = fighter;
        runLater(() -> {
            if (!Objects.equals(expected, fighter)) return;
            UUID previous = fighter; fighter = null; removeBot(); clearTurnEntities(); live = false;
            Player player = previous == null ? null : Bukkit.getPlayer(previous);
            if (player != null) {
                try { spectate(player); }
                catch (RuntimeException failure) { manager.failed(failure); return; }
            } else if (previous != null) { entered.remove(previous); watchers.remove(previous); }
            nextTurn = tick + 40; resettingTurn = false;
        }, 1);
    }
    private void disconnected(UUID id) {
        run.disconnect(id); fighter = null; live = false; resettingTurn = false; removeBot(); clearTurnEntities();
        entered.remove(id); watchers.remove(id); manager.save(); nextTurn = tick + 20; manager.refresh();
    }
    void withdraw(UUID id) {
        spectatorOptOuts.add(id);
        run.withdraw(id);
        if (Objects.equals(fighter, id)) { fighter = null; live = false; resettingTurn = false; removeBot(); clearTurnEntities(); nextTurn = tick + 20; }
        returnPlayer(id); manager.save();
    }
    void watch(Player player) {
        UUID id = player.getUniqueId();
        if (playing(id)) throw new IllegalStateException("Finish your turn before spectating.");
        if (watchers.contains(id)) return;
        if (contender.getDuelManager().getDuel(id) != null || contender.getMinigameManager().pendingReturn(id) || contender.getMinigameManager().owns(id)) throw new IllegalStateException("Return to the lobby before spectating.");
        spectate(player); spectatorOptOuts.remove(id);
    }
    private void spectate(Player player) {
        UUID id = player.getUniqueId();
        if (!owns(id)) manager.capture(player);
        entered.add(id); watchers.add(id);
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        if (!teleport(player, lease.instance().map().getSpectatorSpawn())) { returnPlayer(id); throw new IllegalStateException("The spectator teleport was blocked."); }
        player.setGameMode(GameMode.SPECTATOR); player.setFallDistance(0); player.setVelocity(new Vector()); full(player);
        contender.refreshVoiceRouting(); contender.refreshHackerAttributes();
    }
    boolean unwatch(Player player) {
        UUID id = player.getUniqueId();
        if (!watchers.contains(id) || Objects.equals(fighter, id)) return false;
        spectatorOptOuts.add(id);
        entered.remove(id); watchers.remove(id);
        if (!manager.restore(player)) me.advait.contender.dialog.Dialogs.tell(player, "The lobby teleport was blocked. Your return will be retried.");
        contender.refreshVoiceRouting(); contender.refreshHackerAttributes();
        return true;
    }
    private void returnPlayer(UUID id) {
        entered.remove(id); watchers.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (player != null) manager.restore(player);
    }
    private boolean ourBot(Entity entity) { return bot != null && bot.getUniqueId().equals(entity.getUniqueId()); }
    private static boolean sword(Player player) { return player.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD"); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (ourBot(event.getEntity())) {
            if (!(event instanceof EntityDamageByEntityEvent hit) || !(hit.getDamager() instanceof Player player)
                    || !playing(player.getUniqueId()) || resettingTurn || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !sword(player)) { event.setCancelled(true); return; }
            HurtRules.removeHealthDamage(event);
        } else if (event.getEntity() instanceof Player player && owns(player.getUniqueId())) {
            if (event instanceof EntityDamageByEntityEvent hit && ourBot(hit.getDamager()) && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && playing(player.getUniqueId()) && live && !resettingTurn) {
                HurtRules.removeHealthDamage(event);
            } else event.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void score(EntityDamageByEntityEvent event) {
        if (event.getDamage() <= 0 || resettingTurn) return;
        if (ourBot(event.getEntity()) && event.getDamager() instanceof Player player && playing(player.getUniqueId())
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && sword(player) && lastAcceptedHit != tick) {
            if (run.hit(player.getUniqueId())) {
                lastAcceptedHit = tick; attacks.hit(tick); live = true;
                ComboAppearance.hurt(bot);
            }
        } else if (event.getEntity() instanceof Player player && playing(player.getUniqueId()) && ourBot(event.getDamager()) && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && live) {
            // A fully blocked sword swing does not break the combo.
            if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                    && event.getDamage() + event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) <= 0) return;
            switch (run.hitBack()) { case RETRY -> retry(); case SCORED -> scored(); case IGNORED -> { } }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(PlayerMoveEvent event) {
        if (!owns(event.getPlayer().getUniqueId()) || internalTeleport || event.getTo() == null) return;
        if (!contains(event.getTo())) { event.setTo(event.getFrom()); return; }
        if (playing(event.getPlayer().getUniqueId()) && event.getTo().getY() < lease.instance().map().getBounds().minY() - 3) {
            // Falling is an unsuccessful turn; early falls use the same retry grace as a returned hit.
            if (run.hits() == 0 || run.hits() <= run.graceHits()) { run.hitBack(); retry(); }
            else { run.hitBack(); scored(); }
            event.setTo(lease.instance().map().getTeam1Spawn()); event.getPlayer().setVelocity(new Vector());
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(PlayerTeleportEvent event) {
        if (owns(event.getPlayer().getUniqueId()) && !internalTeleport) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) { if (playing(e.getPlayer().getUniqueId())) { e.setUseInteractedBlock(Event.Result.DENY); e.setUseItemInHand(Event.Result.ALLOW); } }
    @EventHandler(priority = EventPriority.HIGHEST) public void entityInteract(PlayerInteractEntityEvent e) { if (ourBot(e.getRightClicked())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void food(FoodLevelChangeEvent e) { if (owns(e.getEntity().getUniqueId())) { e.setCancelled(true); e.setFoodLevel(20); } }
    @EventHandler public void hunger(EntityExhaustionEvent e) { if (owns(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void fishing(PlayerFishEvent e) { if (e.getCaught() != null && ourBot(e.getCaught())) e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e) { if (owns(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void pickup(EntityPickupItemEvent e) { if (owns(e.getEntity().getUniqueId()) || ourBot(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void inventory(InventoryClickEvent e) { if (owns(e.getWhoClicked().getUniqueId()) && e.getClickedInventory() != e.getWhoClicked().getInventory()) e.setCancelled(true); }
    @EventHandler public void drag(InventoryDragEvent e) { if (owns(e.getWhoClicked().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void botTeleport(EntityTeleportEvent e) { if (ourBot(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void portal(EntityPortalEvent e) { if (ourBot(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void advancement(PlayerAdvancementDoneEvent e) { if (owns(e.getPlayer().getUniqueId())) e.message(null); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (Objects.equals(fighter, id)) disconnected(id);
        else { entered.remove(id); watchers.remove(id); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void death(PlayerDeathEvent e) {
        if (!owns(e.getPlayer().getUniqueId())) return;
        e.setKeepInventory(true); e.getDrops().clear(); e.setDroppedExp(0); e.deathMessage(null); withdraw(e.getPlayer().getUniqueId());
    }
}
