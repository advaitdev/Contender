package me.advait.contender.map;

import me.advait.contender.Contender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class MapManager {

    private final Contender plugin;
    private final File mapFile;
    private final Map<String, ArenaMap> maps;

    public MapManager(Contender plugin) {
        this.plugin = plugin;
        this.mapFile = new File(plugin.getDataFolder(), "maps.yml");
        this.maps = new LinkedHashMap<>();
        loadMaps();
    }

    public void loadMaps() {
        maps.clear();

        if (!mapFile.exists()) {
            plugin.saveResource("maps.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(mapFile);
        ConfigurationSection mapsSection = config.getConfigurationSection("maps");
        if (mapsSection == null) return;

        for (String id : mapsSection.getKeys(false)) {
            ConfigurationSection section = mapsSection.getConfigurationSection(id);
            if (section == null) continue;

            ArenaMap map = new ArenaMap(id);
            map.setDisplayName(section.getString("display-name", id));
            map.setWorldName(section.getString("world", "world"));
            map.setSchematic(section.getString("schematic"));
            map.setCopies(Math.clamp(section.getInt("copies", plugin.getConfig().getInt("arenas.copies-per-map", 20)), 1, 100));
            map.setFirstSlot(section.getInt("first-slot", -1));

            ConfigurationSection t1 = section.getConfigurationSection("team1-spawn");
            if (t1 != null) {
                map.setTeam1Spawn(
                        t1.getDouble("x"), t1.getDouble("y"), t1.getDouble("z"),
                        (float) t1.getDouble("yaw"), (float) t1.getDouble("pitch")
                );
            }

            ConfigurationSection t2 = section.getConfigurationSection("team2-spawn");
            if (t2 != null) {
                map.setTeam2Spawn(
                        t2.getDouble("x"), t2.getDouble("y"), t2.getDouble("z"),
                        (float) t2.getDouble("yaw"), (float) t2.getDouble("pitch")
                );
            }

            ConfigurationSection spec = section.getConfigurationSection("spectator-spawn");
            if (spec != null) {
                map.setSpectatorSpawn(
                        spec.getDouble("x"), spec.getDouble("y"), spec.getDouble("z"),
                        (float) spec.getDouble("yaw"), (float) spec.getDouble("pitch")
                );
            }

            ConfigurationSection rb = section.getConfigurationSection("rollback-region");
            if (rb != null) {
                ConfigurationSection c1 = rb.getConfigurationSection("corner1");
                ConfigurationSection c2 = rb.getConfigurationSection("corner2");
                if (c1 != null && c2 != null) {
                    map.setRollbackRegion(
                            c1.getDouble("x"), c1.getDouble("y"), c1.getDouble("z"),
                            c2.getDouble("x"), c2.getDouble("y"), c2.getDouble("z")
                    );
                }
            }

            maps.put(id, map);
        }
    }

    public ArenaMap getMap(String id) {
        return maps.get(id);
    }

    public Collection<ArenaMap> getMaps() {
        return Collections.unmodifiableCollection(maps.values());
    }

    public boolean mapExists(String id) {
        return maps.containsKey(id);
    }

    public void save(ArenaMap map) {
        maps.put(map.getId(), map);
        YamlConfiguration config = new YamlConfiguration();
        for (ArenaMap entry : maps.values()) {
            String path = "maps." + entry.getId();
            config.set(path + ".display-name", entry.getDisplayName());
            config.set(path + ".world", entry.getWorldName());
            config.set(path + ".schematic", entry.getSchematic());
            config.set(path + ".copies", entry.getCopies());
            config.set(path + ".first-slot", entry.getFirstSlot());
            writeSpawn(config, path + ".team1-spawn", entry.getTeam1Point());
            writeSpawn(config, path + ".team2-spawn", entry.getTeam2Point());
            writeSpawn(config, path + ".spectator-spawn", entry.getSpectatorPoint());
            BlockBounds b = entry.getBounds();
            if (b != null) {
                config.set(path + ".rollback-region.corner1.x", b.minX());
                config.set(path + ".rollback-region.corner1.y", b.minY());
                config.set(path + ".rollback-region.corner1.z", b.minZ());
                config.set(path + ".rollback-region.corner2.x", b.maxX());
                config.set(path + ".rollback-region.corner2.y", b.maxY());
                config.set(path + ".rollback-region.corner2.z", b.maxZ());
            }
        }
        me.advait.contender.util.YamlStorage.save(config, mapFile);
    }

    private void writeSpawn(YamlConfiguration config, String path, SpawnPoint spawn) {
        if (spawn == null) return;
        config.set(path + ".x", spawn.x());
        config.set(path + ".y", spawn.y());
        config.set(path + ".z", spawn.z());
        config.set(path + ".yaw", spawn.yaw());
        config.set(path + ".pitch", spawn.pitch());
    }

    public int allocateSlots(ArenaMap map) {
        if (map.getFirstSlot() >= 0) return map.getFirstSlot();
        // Reserve 100 slots per map so its copy count can grow without overlap.
        int slot = maps.values().stream().filter(m -> m.getFirstSlot() >= 0)
                .mapToInt(m -> m.getFirstSlot() + 100).max().orElse(0);
        map.setFirstSlot(slot);
        save(map);
        return slot;
    }
}
