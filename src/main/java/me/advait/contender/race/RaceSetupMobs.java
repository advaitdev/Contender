package me.advait.contender.race;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import me.advait.contender.Contender;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.map.ArenaMap;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Supplier;

/** Protects original course mobs without intercepting hits on the arena copies. */
final class RaceSetupMobs extends AbstractGameState {
    static final NamespacedKey SOURCE_MAP = new NamespacedKey("contender", "race_source_map");
    private final Contender contender;
    private final Supplier<Collection<RaceCourse>> courses;
    private final Set<String> watching = new HashSet<>();
    private final Map<UUID, LivingEntity> protectedMobs = new HashMap<>();

    RaceSetupMobs(Contender plugin, Supplier<Collection<RaceCourse>> courses) {
        super(plugin);
        this.contender = plugin;
        this.courses = courses;
    }

    @Override protected void onEnable() {
        // Tags also recover unfinished setup work after a restart.
        Bukkit.getWorlds().forEach(world -> world.getLivingEntities().forEach(this::recognize));
        runRepeating(this::refresh, 1, 20);
    }

    @Override protected void onDisable() {
        protectedMobs.clear();
        watching.clear();
    }

    void watch(String mapId) {
        watching.add(mapId);
        ArenaMap map = contender.getMapManager().getMap(mapId);
        World world = map == null ? null : Bukkit.getWorld(map.getWorldName());
        if (world != null) world.getLivingEntities().forEach(this::recognize);
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) { if (recognize(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        if (!recognize(event.getEntity())) return;
        event.setCancelled(true);
        event.setReviveHealth(event.getEntity().getMaxHealth());
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
