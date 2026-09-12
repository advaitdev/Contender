package me.advait.contender.race;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import me.advait.contender.Contender;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.util.YamlStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Protects original course mobs without intercepting hits on the arena copies. */
final class RaceSetupMobs extends AbstractGameState {
    static final NamespacedKey SOURCE_MAP = new NamespacedKey("contender", "race_source_map");
    private final Contender contender;
    private final Supplier<Collection<RaceCourse>> courses;
    private final Set<String> watching = new HashSet<>();
    private final Map<UUID, LivingEntity> protectedMobs = new HashMap<>();
    private final Set<UUID> killedMobs = new HashSet<>();
    private final Map<String, OrderRepair> repairs = new HashMap<>();
    private final Set<String> dirtyOrder = new HashSet<>();
    private File pendingFile;

    private static final class OrderRepair {
        final CompletableFuture<Void> complete = new CompletableFuture<>();
        final Set<UUID> removed = new HashSet<>();
        final Set<Chunk> tickets = new HashSet<>();
    }

    RaceSetupMobs(Contender plugin, Supplier<Collection<RaceCourse>> courses) {
        super(plugin);
        this.contender = plugin;
        this.courses = courses;
    }

    @Override protected void onEnable() {
        pendingFile = new File(contender.getDataFolder(), "race-course-edits.yml");
        dirtyOrder.addAll(YamlConfiguration.loadConfiguration(pendingFile).getStringList("pending"));
        watching.addAll(dirtyOrder);
        // Tags also recover unfinished setup work after a restart.
        Bukkit.getWorlds().forEach(world -> world.getLivingEntities().forEach(this::recognize));
        runRepeating(this::refresh, 1, 20);
        if (!dirtyOrder.isEmpty()) runLater(() -> List.copyOf(dirtyOrder).forEach(this::resumeRepair), 1);
    }

    @Override protected void onDisable() {
        List<OrderRepair> interrupted = List.copyOf(repairs.values());
        repairs.clear();
        interrupted.forEach(repair -> {
            repair.complete.completeExceptionally(new IllegalStateException("Course editing stopped."));
            repair.tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
            repair.tickets.clear();
        });
        protectedMobs.clear();
        killedMobs.clear();
        watching.clear();
        dirtyOrder.clear();
    }

    /** Returns the source course only; copied arena tags never authorize an edit. */
    String checkpointMap(Entity entity) {
        if (!(entity instanceof LivingEntity mob) || mob instanceof Player || mob.customName() == null) return null;
        String tagged = mob.getPersistentDataContainer().get(SOURCE_MAP, PersistentDataType.STRING);
        if (tagged != null && inSource(mob, tagged)) return tagged;
        for (String mapId : sourceIds()) if (inSource(mob, mapId)) return mapId;
        return null;
    }

    boolean remove(LivingEntity mob, String mapId) {
        if (!isEnabled() || mapId == null || !mob.isValid() || mob.isDead() || !mapId.equals(checkpointMap(mob))) return false;
        OrderRepair repair = orderRepair(mapId);
        repair.removed.add(mob.getUniqueId());
        protectedMobs.remove(mob.getUniqueId());
        mob.remove();
        return true;
    }

    boolean repairing(String mapId) { return repairs.containsKey(mapId); }

    CompletableFuture<Void> settled(String mapId) {
        OrderRepair repair = repairs.get(mapId);
        if (repair != null) return repair.complete;
        return dirtyOrder.contains(mapId) ? repairOrder(mapId) : CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> repairOrder(String mapId) { return orderRepair(mapId).complete; }

    private OrderRepair orderRepair(String mapId) {
        OrderRepair existing = repairs.get(mapId);
        if (existing != null) return existing;
        if (!isEnabled()) throw new IllegalStateException("Course editing stopped.");
        OrderRepair repair = new OrderRepair();
        dirtyOrder.add(mapId);
        savePending();
        repairs.put(mapId, repair);
        // Removal callbacks may run before the entity leaves its chunk, and bulk deletion
        // can remove several checkpoints in one tick. Wait before reading the survivors.
        runLater(() -> repair(mapId, repair), 1);
        return repair;
    }

    private void repair(String mapId, OrderRepair repair) {
        ArenaMap map = contender.getMapManager().getMap(mapId);
        CompletableFuture<Void> loaded;
        if (map == null || map.getBounds() == null) {
            loaded = CompletableFuture.failedFuture(new IllegalStateException("The course map no longer exists."));
        } else {
            try {
                loaded = RaceManager.loadChunks(contender, map, repair.tickets,
                        () -> isEnabled() && repairs.get(mapId) == repair);
            } catch (RuntimeException failure) {
                loaded = CompletableFuture.failedFuture(failure);
            }
        }
        loaded.thenRun(() -> {
            if (!isEnabled() || repairs.get(mapId) != repair) throw new IllegalStateException("Course editing stopped.");
            RaceCourse course = course(mapId);
            List<LivingEntity> checkpoints = RaceManager.entities(map).stream()
                    .filter(mob -> mob.isValid() && !mob.isDead() && !repair.removed.contains(mob.getUniqueId()))
                    .filter(mob -> {
                        int number = course.checkpoint(RaceManager.plainName(mob));
                        return number > 0 && number < Integer.MAX_VALUE;
                    })
                    .sorted(Comparator.comparingInt((LivingEntity mob) -> course.checkpoint(RaceManager.plainName(mob)))
                            .thenComparing(Entity::getUniqueId))
                    .toList();
            for (int i = 0; i < checkpoints.size(); i++) {
                LivingEntity mob = checkpoints.get(i);
                String name = "#" + (i + 1);
                if (!name.equals(RaceManager.plainName(mob))) mob.customName(Component.text(name, NamedTextColor.GOLD));
                if (protectedMobs.get(mob.getUniqueId()) != mob) protect(mob, mapId);
            }
            dirtyOrder.remove(mapId);
            try { savePending(); }
            catch (RuntimeException failure) { dirtyOrder.add(mapId); throw failure; }
        }).whenComplete((ignored, failure) -> {
            repair.tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
            repair.tickets.clear();
            repairs.remove(mapId, repair);
            if (failure == null) {
                repair.complete.complete(null);
            }
            else {
                repair.complete.completeExceptionally(failure);
                if (isEnabled()) contender.getLogger().warning("Could not reorder checkpoints in " + mapId + ": " + RaceManager.rootMessage(failure));
            }
        });
    }

    private void savePending() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pending", dirtyOrder.stream().sorted().toList());
        YamlStorage.save(yaml, pendingFile);
    }

    private void resumeRepair(String mapId) {
        ArenaMap map = contender.getMapManager().getMap(mapId);
        if (dirtyOrder.contains(mapId) && map != null && Bukkit.getWorld(map.getWorldName()) != null) orderRepair(mapId);
    }

    void watch(String mapId) {
        watching.add(mapId);
        ArenaMap map = contender.getMapManager().getMap(mapId);
        World world = map == null ? null : Bukkit.getWorld(map.getWorldName());
        if (world != null) world.getLivingEntities().forEach(this::recognize);
        resumeRepair(mapId);
    }

    void protect(LivingEntity mob, String mapId) {
        if (mob instanceof Player) return;
        watching.add(mapId);
        protectedMobs.put(mob.getUniqueId(), mob);
        mob.getPersistentDataContainer().set(SOURCE_MAP, PersistentDataType.STRING, mapId);
        mob.setAI(false);
        mob.setGravity(false);
        mob.setInvulnerable(true);
        mob.setCollidable(false);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.setPersistent(true);
        mob.setCustomNameVisible(true);
        mob.setSilent(true);
        mob.setFireTicks(0);
        mob.setHealth(mob.getMaxHealth());
        mob.setVelocity(new Vector());
        mob.leaveVehicle();
        mob.eject();
        if (mob.isLeashed()) mob.setLeashHolder(null);
        if (mob instanceof Mob aiMob && aiMob.shouldDespawnInPeaceful() && mob.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            // The per-mob Peaceful override is a no-op in 26.2, and clients hide hostile mobs.
            mob.getWorld().setDifficulty(Difficulty.NORMAL);
            contender.getLogger().info("Set " + mob.getWorld().getName() + " to Normal difficulty to keep hostile race checkpoints.");
        }
    }

    private void refresh() {
        protectedMobs.values().removeIf(mob -> !mob.isValid() || mob.isDead());
        Set<World> worlds = new HashSet<>();
        for (String mapId : sourceIds()) {
            ArenaMap map = contender.getMapManager().getMap(mapId);
            World world = map == null ? null : Bukkit.getWorld(map.getWorldName());
            if (world != null) worlds.add(world);
        }
        // Only loaded entities in source worlds; never load the entire course to scan it.
        worlds.forEach(world -> world.getLivingEntities().forEach(this::recognize));
        protectedMobs.values().forEach(mob -> { mob.setVelocity(new Vector()); mob.setFireTicks(0); });
    }

    private Set<String> sourceIds() {
        Set<String> ids = new HashSet<>(watching);
        courses.get().forEach(course -> ids.add(course.mapId()));
        return ids;
    }

    private RaceCourse course(String mapId) {
        return courses.get().stream().filter(course -> course.mapId().equals(mapId)).findFirst()
                .orElseGet(() -> RaceCourse.defaults(mapId));
    }

    private boolean inSource(LivingEntity mob, String mapId) {
        ArenaMap map = contender.getMapManager().getMap(mapId);
        return map != null && map.contains(mob.getLocation()) && course(mapId).checkpoint(RaceManager.plainName(mob)) > 0;
    }

    private boolean recognize(Entity entity) {
        if (!(entity instanceof LivingEntity mob) || entity instanceof Player) return false;
        if (killedMobs.contains(entity.getUniqueId())) return false;
        if (protectedMobs.get(entity.getUniqueId()) == mob) return true;
        if (mob.customName() == null) return false;
        String tagged = mob.getPersistentDataContainer().get(SOURCE_MAP, PersistentDataType.STRING);
        if (tagged != null && inSource(mob, tagged)) { protect(mob, tagged); return true; }
        for (String mapId : sourceIds()) {
            if (inSource(mob, mapId)) { protect(mob, mapId); return true; }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) { recognize(event.getEntity()); }

    @EventHandler public void load(EntitiesLoadEvent event) { event.getEntities().forEach(this::recognize); }

    @EventHandler public void worldLoad(WorldLoadEvent event) {
        for (String mapId : List.copyOf(dirtyOrder)) {
            ArenaMap map = contender.getMapManager().getMap(mapId);
            if (map != null && map.getWorldName().equals(event.getWorld().getName())) resumeRepair(mapId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void remove(EntityRemoveEvent event) {
        if (!isEnabled()) return;
        protectedMobs.remove(event.getEntity().getUniqueId());
        boolean alreadyKilled = killedMobs.remove(event.getEntity().getUniqueId());
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD || event.getCause() == EntityRemoveEvent.Cause.PLAYER_QUIT) return;
        String mapId = checkpointMap(event.getEntity());
        if (alreadyKilled && !dirtyOrder.contains(mapId)) return;
        if (mapId != null) orderRepair(mapId).removed.add(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.KILL && recognize(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        if (explicitKill(event)) return;
        if (!recognize(event.getEntity())) return;
        event.setCancelled(true);
        event.setReviveHealth(event.getEntity().getMaxHealth());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void killed(EntityDeathEvent event) {
        if (!isEnabled() || !explicitKill(event)) return;
        String mapId = checkpointMap(event.getEntity());
        if (mapId == null) return;
        UUID id = event.getEntity().getUniqueId();
        killedMobs.add(id);
        protectedMobs.remove(id);
        orderRepair(mapId).removed.add(id);
    }

    private static boolean explicitKill(EntityDeathEvent event) {
        return event.getDamageSource() != null && event.getDamageSource().getDamageType() != null
                && NamespacedKey.minecraft("generic_kill").equals(event.getDamageSource().getDamageType().getKey());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(EntityMoveEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void knockback(EntityKnockbackEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void combust(EntityCombustEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(ExplosionPrimeEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucket(PlayerBucketEntityEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transform(EntityTransformEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mount(EntityMountEvent event) {
        if (recognize(event.getEntity()) || recognize(event.getMount())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void leash(PlayerLeashEntityEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }
}
