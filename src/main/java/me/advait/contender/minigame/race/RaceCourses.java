package me.advait.contender.minigame.race;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.util.YamlStorage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Saved race courses, plus a one-time import of courses built for the old named-mob setup. */
public final class RaceCourses {
    private final Contender plugin;
    private final File file;
    private final Map<String, RaceCourse> courses = new LinkedHashMap<>();

    public RaceCourses(Contender plugin) {
        this.plugin = plugin;
        file = new File(new File(plugin.getDataFolder(), "minigames"), "race-courses.yml");
        load();
    }

    public Collection<RaceCourse> all() { return Collections.unmodifiableCollection(courses.values()); }
    public RaceCourse get(String id) { return courses.get(id); }

    public RaceCourse create(String name, World world) {
        String id = MapManager.idFor(name);
        if (id.isEmpty()) throw new IllegalArgumentException("Include a letter or number in the name.");
        if (courses.containsKey(id)) throw new IllegalArgumentException("A course with that name already exists.");
        RaceCourse course = new RaceCourse(id, name.strip(), world.getName());
        courses.put(id, course);
        save();
        return course;
    }

    public void delete(String id) {
        if (courses.remove(id) != null) save();
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("courses");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(id);
            if (c == null) continue;
            try {
                RaceCourse course = new RaceCourse(id, c.getString("name", id), c.getString("world", "world"));
                course.rules(c.getInt("max-jump", 15), c.getDouble("return-height", RaceCourse.DEFAULT_RETURN_HEIGHT), c.getBoolean("return-on-ground", true));
                RaceCourse.Checkpoint start = c.isConfigurationSection("start") ? read(c.getConfigurationSection("start")) : null;
                List<RaceCourse.Checkpoint> points = new ArrayList<>();
                for (Map<?, ?> row : c.getMapList("checkpoints")) points.add(read(row));
                course.load(start, points);
                courses.put(id, course);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Skipping race course " + id + ": " + failure.getMessage());
            }
        }
    }

    private static RaceCourse.Checkpoint read(ConfigurationSection s) {
        return new RaceCourse.Checkpoint(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), type(s.getString("type")));
    }

    private static RaceCourse.Checkpoint read(Map<?, ?> row) {
        return new RaceCourse.Checkpoint(number(row.get("x")), number(row.get("y")), number(row.get("z")), (float) number(row.get("yaw")),
                type(row.get("type") == null ? null : row.get("type").toString()));
    }

    private static double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }

    private static EntityType type(String name) {
        try { return name == null ? EntityType.ZOMBIE : EntityType.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return EntityType.ZOMBIE; }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (RaceCourse course : courses.values()) {
            String path = "courses." + course.id();
            yaml.set(path + ".name", course.name());
            yaml.set(path + ".world", course.worldName());
            yaml.set(path + ".max-jump", course.maxJump());
            yaml.set(path + ".return-height", course.returnHeight());
            yaml.set(path + ".return-on-ground", course.returnOnGround());
            if (course.start() != null) {
                yaml.set(path + ".start.x", course.start().x());
                yaml.set(path + ".start.y", course.start().y());
                yaml.set(path + ".start.z", course.start().z());
                yaml.set(path + ".start.yaw", course.start().yaw());
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (RaceCourse.Checkpoint point : course.checkpoints()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("x", point.x());
                row.put("y", point.y());
                row.put("z", point.z());
                row.put("yaw", (double) point.yaw());
                row.put("type", point.type().name());
                points.add(row);
            }
            yaml.set(path + ".checkpoints", points);
        }
        YamlStorage.save(yaml, file);
    }

    // ---- Import from the old setup -------------------------------------------------------------

    /** Courses saved by older versions, which used mobs named #1, #2 … placed in a saved map. */
    public List<String> legacyCourses() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "mace-race.yml"));
        ConfigurationSection section = old.getConfigurationSection("courses");
        if (section == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (String id : section.getKeys(false)) if (plugin.getMapManager().getMap(id) != null && !courses.containsKey(id)) ids.add(id);
        return ids;
    }

    /**
     * Turns an old course into a new one: every mob named #n (and the finish mob) in the old map becomes a
     * checkpoint at the same spot, then those mobs are removed so they don't double up with the race's own.
     */
    public CompletableFuture<RaceCourse> importLegacy(String mapId) {
        ArenaMap map = plugin.getMapManager().getMap(mapId);
        if (map == null || map.getBounds() == null) return CompletableFuture.failedFuture(new IllegalArgumentException("That old course's map is gone."));
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "mace-race.yml"));
        String finish = old.getString("courses." + mapId + ".finish-name", "Finish");
        World world;
        try {
            world = Bukkit.getWorld(map.getWorldName());
            if (world == null) world = plugin.getManagedWorlds().loadExisting(map.getWorldName());
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        World loaded = world;
        var bounds = map.getBounds();
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
            loads.add(loaded.getChunkAtAsync(x, z));
        }
        Set<Chunk> held = new HashSet<>();
        CompletableFuture<RaceCourse> result = new CompletableFuture<>();
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure != null) { result.completeExceptionally(failure); return; }
            for (var load : loads) { Chunk chunk = load.join(); if (chunk.addPluginChunkTicket(plugin)) held.add(chunk); }
            // Entities load just after their chunk; give them a moment.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try { result.complete(convert(mapId, map, loaded, finish, old)); }
                catch (RuntimeException error) { result.completeExceptionally(error); }
                finally { held.forEach(chunk -> chunk.removePluginChunkTicket(plugin)); }
            }, 10L);
        });
        return result;
    }

    private RaceCourse convert(String mapId, ArenaMap map, World world, String finishName, YamlConfiguration old) {
        TreeMap<Integer, LivingEntity> numbered = new TreeMap<>();
        LivingEntity finish = null;
        var bounds = map.getBounds();
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
            for (Entity entity : world.getChunkAt(x, z).getEntities()) {
                if (!(entity instanceof LivingEntity mob) || entity instanceof Player || mob.customName() == null || !map.contains(mob.getLocation())) continue;
                String name = PlainTextComponentSerializer.plainText().serialize(mob.customName()).strip();
                if (name.equals(finishName)) finish = mob;
                else if (name.matches("#[1-9][0-9]{0,4}")) numbered.putIfAbsent(Integer.parseInt(name.substring(1)), mob);
            }
        }
        if (numbered.isEmpty() && finish == null) throw new IllegalStateException("No checkpoint mobs were found in " + map.getDisplayName() + ".");
        RaceCourse course = new RaceCourse(mapId, map.getDisplayName(), world.getName());
        String path = "courses." + mapId;
        course.rules(old.getInt(path + ".max-advance", 15), old.getDouble(path + ".return-height", RaceCourse.DEFAULT_RETURN_HEIGHT), old.getBoolean(path + ".return-on-ground", true));
        if (map.getTeam1Spawn() != null) course.setStart(map.getTeam1Spawn());
        List<LivingEntity> order = new ArrayList<>(numbered.values());
        if (finish != null) order.add(finish);
        for (LivingEntity mob : order) course.add(mob.getLocation(), mob.getType());
        for (LivingEntity mob : order) mob.remove();
        courses.put(course.id(), course);
        save();
        return course;
    }
}
