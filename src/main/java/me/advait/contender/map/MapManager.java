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
}
