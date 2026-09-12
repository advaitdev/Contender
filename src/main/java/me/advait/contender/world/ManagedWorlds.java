package me.advait.contender.world;

import me.advait.contender.Contender;
import me.advait.contender.arena.VoidGenerator;
import me.advait.contender.util.YamlStorage;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/** Loads saved worlds on the server thread, without creating missing replacement worlds. */
public final class ManagedWorlds implements Listener {
    private final Contender plugin;
    private final Server server;
    private final Path savedFile;
    private final Map<NamespacedKey, SavedWorld> remembered = new LinkedHashMap<>();
    private final Map<NamespacedKey, ChunkGenerator> liveGenerators = new LinkedHashMap<>();
    private boolean readSaved;

    private record SavedWorld(String name, NamespacedKey key, World.Environment environment,
                              boolean voidGenerator, String generatorPlugin, String generatorClass) { }

    public ManagedWorlds(Contender plugin) {
        this.plugin = plugin;
        server = plugin.getServer();
        savedFile = plugin.getDataFolder().toPath().resolve("managed-worlds.yml");
    }

    public World loadExisting(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The saved world has no name.");
        World loaded = server.getWorld(name);
        if (loaded != null) {
            rememberSafely(loaded);
            return loaded;
        }
        try {
            readRemembered();
            Path container = server.getWorldContainer().toPath().toAbsolutePath().normalize();
            Path level = server.getLevelDirectory().toAbsolutePath().normalize();
            NamespacedKey requested = key(name, level);
            List<SavedWorld> catalog = builderWorlds(level);
            SavedWorld saved = resolve(catalog, name, requested);
            if (saved == null) saved = resolve(new ArrayList<>(remembered.values()), name, requested);
            NamespacedKey worldKey = saved == null ? requested : saved.key();
            loaded = server.getWorld(worldKey);
            if (loaded != null) {
                rememberSafely(loaded);
                return loaded;
            }

            Path dimension = level.resolve("dimensions").resolve(worldKey.getNamespace()).resolve(worldKey.getKey()).normalize();
            Path legacy = legacy(container, level, worldKey);
            if (Files.exists(dimension) && legacy != null) {
                throw new IllegalStateException("Both old and migrated folders exist for " + name + ". Resolve the duplicate before loading it.");
            }
            WorldCreator creator;
            Path source;
            if (Files.exists(dimension)) {
                if (!Files.isRegularFile(dimension.resolve("data/minecraft/world_gen_settings.dat"))) {
                    throw new IllegalStateException("World " + name + " is missing its saved generation data. Restore that world's files before loading it.");
                }
                creator = WorldCreator.ofKey(worldKey);
                source = dimension;
            } else if (legacy != null) {
                creator = new WorldCreator(legacy.getFileName().toString());
                source = legacy;
            } else {
                throw new IllegalArgumentException("World " + name + " was not found. Restore its folder under " + container + " or " + level.resolve("dimensions") + ".");
            }
            if (!creator.key().equals(worldKey)) throw new IllegalStateException("The saved world name and key disagree for " + name + ". Check its Builder entry.");
            Path apiSource = container.resolve(creator.name()).normalize();
            if (isLegacy(apiSource) && !apiSource.equals(source)) {
                throw new IllegalStateException("Another old world folder uses the name " + creator.name() + ". Resolve the duplicate before loading it.");
            }
            World.Environment environment = saved == null ? null : saved.environment();
            if (environment == null) environment = knownEnvironment(worldKey, legacy);
            if (environment == null) {
                throw new IllegalStateException("World " + name + " has no saved world type. Load it once with Builder using /loadworld "
                        + worldKey + " <normal|flat|void|end|nether>.");
            }
            if (environment == World.Environment.CUSTOM) {
                throw new IllegalStateException("World " + name + " uses a custom dimension. Load it with its world plugin before preparing the map.");
            }
            creator.environment(environment);
            if (saved != null) configureGenerator(creator, saved);
            World world = server.createWorld(creator);
            if (world == null) throw new IllegalStateException("The server could not load world " + name + ". Check the server log.");
            if (!worldKey.equals(world.getKey())) throw new IllegalStateException("The server loaded a different world for " + name + ". Check its saved name and key.");
            rememberSafely(world);
            return world;
        } catch (IOException error) {
            throw new IllegalStateException("Could not read the saved world " + name + ": " + error.getMessage(), error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) { rememberSafely(event.getWorld()); }

    private void rememberSafely(World world) {
        try { remember(world); }
        catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not remember the world type for " + world.getName() + ".", error);
        }
    }

    /** Remember the actual environment before an external world manager unloads the world. */
    public void remember(World world) {
        if (world.getKey() == null || world.getEnvironment() == null) return;
        try { readRemembered(); }
        catch (IOException error) { throw new IllegalStateException("Could not read managed-worlds.yml.", error); }
        ChunkGenerator generator = world.getGenerator();
        boolean voidGenerator = generator instanceof VoidGenerator || generator != null
                && generator.getClass().getName().equals("me.advait.builder.world.VoidGenerator");
        String owner = null;
        String generatorClass = null;
        if (generator != null && !voidGenerator) {
            generatorClass = generator.getClass().getName();
            try { owner = JavaPlugin.getProvidingPlugin(generator.getClass()).getName(); }
            catch (IllegalArgumentException ignored) { }
        }
        SavedWorld saved = new SavedWorld(world.getName(), world.getKey(), world.getEnvironment(), voidGenerator, owner, generatorClass);
        if (saved.equals(remembered.get(world.getKey()))) {
            if (generator != null && !voidGenerator) liveGenerators.put(world.getKey(), generator);
            return;
        }
        Map<NamespacedKey, SavedWorld> updated = new LinkedHashMap<>(remembered);
        updated.put(world.getKey(), saved);
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (SavedWorld value : updated.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", value.name());
            entry.put("key", value.key().toString());
            entry.put("environment", value.environment().name());
            entry.put("void-generator", value.voidGenerator());
            if (value.generatorPlugin() != null) entry.put("generator-plugin", value.generatorPlugin());
            if (value.generatorClass() != null) entry.put("generator-class", value.generatorClass());
            worlds.add(entry);
        }
        yaml.set("worlds", worlds);
        YamlStorage.save(yaml, savedFile.toFile());
        remembered.put(world.getKey(), saved);
        if (generator != null && !voidGenerator) liveGenerators.put(world.getKey(), generator);
        else liveGenerators.remove(world.getKey());
    }

    private void configureGenerator(WorldCreator creator, SavedWorld saved) {
        if (saved.voidGenerator()) {
            creator.generator(new VoidGenerator()).generateStructures(false);
        } else if (saved.generatorClass() != null) {
            ChunkGenerator generator = liveGenerators.get(saved.key());
            if (generator == null && saved.generatorPlugin() != null) {
                Plugin provider = server.getPluginManager().getPlugin(saved.generatorPlugin());
                if (provider != null && provider.isEnabled()) generator = provider.getDefaultWorldGenerator(creator.name(), null);
            }
            if (generator == null || !generator.getClass().getName().equals(saved.generatorClass())) {
                throw new IllegalStateException("World " + saved.name() + " uses a custom generator. Load it with its world plugin before preparing the map.");
            }
            creator.generator(generator);
        }
    }

    private void readRemembered() throws IOException {
        if (readSaved) return;
        YamlConfiguration yaml = read(savedFile);
        if (yaml.contains("worlds") && !yaml.isList("worlds")) throw new IOException("Invalid worlds list in managed-worlds.yml.");
        Map<NamespacedKey, SavedWorld> parsed = new LinkedHashMap<>();
        for (Map<?, ?> entry : yaml.getMapList("worlds")) {
            NamespacedKey key;
            try { key = key(Objects.toString(entry.get("key"), ""), server.getLevelDirectory()); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid world key in managed-worlds.yml.", error); }
            World.Environment environment;
            try { environment = World.Environment.valueOf(Objects.toString(entry.get("environment"), "")); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid world environment in managed-worlds.yml.", error); }
            parsed.put(key, new SavedWorld(Objects.toString(entry.get("name"), key.toString()), key, environment,
                    Boolean.TRUE.equals(entry.get("void-generator")), string(entry.get("generator-plugin")), string(entry.get("generator-class"))));
        }
        remembered.putAll(parsed);
        readSaved = true;
    }

    private List<SavedWorld> builderWorlds(Path level) throws IOException {
        Plugin builder = server.getPluginManager().getPlugin("Builder");
        Path file = builder != null ? builder.getDataFolder().toPath().resolve("config.yml")
                : plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve("Builder/config.yml");
        YamlConfiguration yaml = read(file);
        List<SavedWorld> worlds = new ArrayList<>();
        if (yaml.isList("worlds")) {
            for (Map<?, ?> entry : yaml.getMapList("worlds")) {
                String name = Objects.toString(entry.get("name"), "");
                worlds.add(builderWorld(name, key(Objects.toString(entry.get("key"), name), level), string(entry.get("type"))));
            }
        } else {
            ConfigurationSection section = yaml.getConfigurationSection("worlds");
            if (yaml.contains("worlds") && section == null) throw new IOException("Invalid worlds section in Builder/config.yml.");
            if (section != null) for (String name : section.getKeys(false)) {
                worlds.add(builderWorld(name, key(name, level), section.getString(name + ".type")));
            }
        }
        return worlds;
    }

    private SavedWorld builderWorld(String name, NamespacedKey key, String type) throws IOException {
        if (type == null) return remembered.getOrDefault(key, new SavedWorld(name, key, null, false, null, null));
        World.Environment environment = switch (type.toUpperCase(Locale.ROOT)) {
            case "NORMAL", "FLAT", "VOID" -> World.Environment.NORMAL;
            case "END" -> World.Environment.THE_END;
            case "NETHER" -> World.Environment.NETHER;
            default -> throw new IOException("Unknown Builder world type for " + name + ": " + type);
        };
        return new SavedWorld(name, key, environment, type.equalsIgnoreCase("VOID"), null, null);
    }

    private static SavedWorld resolve(List<SavedWorld> worlds, String name, NamespacedKey requested) {
        List<SavedWorld> matches = worlds.stream().filter(world -> world.key().equals(requested)).toList();
        if (matches.isEmpty()) matches = worlds.stream().filter(world -> world.name().equalsIgnoreCase(name)
                || apiName(world.key()).equalsIgnoreCase(name)).toList();
        if (matches.size() > 1) throw new IllegalStateException("Several saved worlds match " + name + ". Use the full namespace:key.");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static String apiName(NamespacedKey key) {
        return key.getNamespace().equals("minecraft") ? key.getKey() : key.getNamespace() + "_" + key.getKey();
    }

    private static NamespacedKey key(String name, Path level) {
        String main = level.getFileName().toString();
        String value = name.contains(":") ? name : "minecraft:" + (name.equalsIgnoreCase(main) ? "overworld"
                : name.equalsIgnoreCase(main + "_nether") ? "the_nether"
                : name.equalsIgnoreCase(main + "_the_end") ? "the_end" : name.toLowerCase(Locale.ROOT).replace(' ', '_'));
        NamespacedKey key = NamespacedKey.fromString(value);
        if (key == null || key.getNamespace().equals(".") || key.getNamespace().equals("..")
                || Arrays.stream(key.getKey().split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Invalid saved world name: " + name + ".");
        }
        return key;
    }

    private static Path legacy(Path container, Path level, NamespacedKey key) throws IOException {
        if (!Files.isDirectory(container)) return null;
        List<Path> matches = new ArrayList<>();
        try (var children = Files.list(container)) {
            for (Path candidate : children.toList()) {
                if (candidate.equals(level) || !isLegacy(candidate)) continue;
                try { if (key(candidate.getFileName().toString(), level).equals(key)) matches.add(candidate); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        if (matches.size() > 1) throw new IllegalStateException("Several old world folders match " + key + ". Resolve the duplicate before loading it.");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static boolean isLegacy(Path path) {
        return Files.isDirectory(path) && (Files.isRegularFile(path.resolve("level.dat")) || Files.isRegularFile(path.resolve("level.dat_old")));
    }

    private static World.Environment knownEnvironment(NamespacedKey key, Path legacy) {
        if (key.equals(NamespacedKey.minecraft("overworld"))) return World.Environment.NORMAL;
        if (key.equals(NamespacedKey.minecraft("the_nether"))) return World.Environment.NETHER;
        if (key.equals(NamespacedKey.minecraft("the_end"))) return World.Environment.THE_END;
        if (legacy == null) return null;
        boolean overworld = Files.isDirectory(legacy.resolve("region"));
        boolean nether = Files.isDirectory(legacy.resolve("DIM-1/region")) || Files.isDirectory(legacy.resolve("dimensions/minecraft/the_nether/region"));
        boolean end = Files.isDirectory(legacy.resolve("DIM1/region")) || Files.isDirectory(legacy.resolve("dimensions/minecraft/the_end/region"));
        if ((overworld ? 1 : 0) + (nether ? 1 : 0) + (end ? 1 : 0) != 1) return null;
        return nether ? World.Environment.NETHER : end ? World.Environment.THE_END : World.Environment.NORMAL;
    }

    private static YamlConfiguration read(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.isRegularFile(file)) {
            try { yaml.load(file.toFile()); }
            catch (org.bukkit.configuration.InvalidConfigurationException error) { throw new IOException("Invalid world configuration in " + file + ".", error); }
        }
        return yaml;
    }

    private static String string(Object value) { return value == null ? null : value.toString(); }
}
