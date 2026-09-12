package me.advait.contender.world;

import me.advait.contender.Contender;
import me.advait.contender.arena.VoidGenerator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagedWorldsTest {
    @TempDir Path directory;
    private Contender plugin;
    private Server server;
    private Path level;
    private Path container;
    private Path builderConfig;
    private Path contenderFolder;
    private final Map<NamespacedKey, World> online = new LinkedHashMap<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() throws Exception {
        container = directory.resolve("worlds");
        level = container.resolve("hub");
        builderConfig = directory.resolve("plugins/Builder/config.yml");
        contenderFolder = directory.resolve("plugins/Contender");
        Files.createDirectories(level);
        Files.createDirectories(builderConfig.getParent());
        Files.createDirectories(contenderFolder);
        plugin = mock(Contender.class);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(contenderFolder.toFile());
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getWorldContainer()).thenReturn(container.toFile());
        when(server.getLevelDirectory()).thenReturn(level);
        when(server.getWorld(any(String.class))).thenAnswer(call -> online.values().stream()
                .filter(world -> world.getName().equals(call.getArgument(0))).findFirst().orElse(null));
        when(server.getWorld(any(NamespacedKey.class))).thenAnswer(call -> online.get(call.getArgument(0)));
        when(server.createWorld(any(WorldCreator.class))).thenAnswer(call -> {
            WorldCreator creator = call.getArgument(0);
            World world = world(creator.name(), creator.key(), creator.environment(), creator.generator());
            online.put(creator.key(), world);
            return world;
        });
        bukkit = mockStatic(Bukkit.class);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.getMainLevelName()).thenReturn("hub");
        bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
    }

    @AfterEach void close() { bukkit.close(); }

    private World world(String name, NamespacedKey key, World.Environment environment, ChunkGenerator generator) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getKey()).thenReturn(key);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.getGenerator()).thenReturn(generator);
        return world;
    }

    private Path migrated(String key) throws Exception {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        Path world = level.resolve("dimensions").resolve(parsed.getNamespace()).resolve(parsed.getKey());
        Files.createDirectories(world.resolve("data/minecraft"));
        Files.writeString(world.resolve("data/minecraft/world_gen_settings.dat"), "saved generation data");
        return world;
    }

    private void builder(String name, String key, String type) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("worlds", List.of(Map.of("name", name, "key", key, "type", type)));
        yaml.save(builderConfig.toFile());
    }

    private WorldCreator creator() {
        ArgumentCaptor<WorldCreator> captor = ArgumentCaptor.forClass(WorldCreator.class);
        verify(server).createWorld(captor.capture());
        return captor.getValue();
    }

    @Test void returnsAlreadyLoadedWorldAndRemembersItsActualEnvironment() {
        World loaded = world("islands", NamespacedKey.minecraft("islands"), World.Environment.THE_END, null);
        online.put(loaded.getKey(), loaded);
        assertSame(loaded, new ManagedWorlds(plugin).loadExisting("islands"));
        verify(server, never()).createWorld(any());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(contenderFolder.resolve("managed-worlds.yml").toFile());
        assertEquals("THE_END", yaml.getMapList("worlds").getFirst().get("environment"));
    }

    @Test void loadsBuilderEndWorldFromItsMigratedFolderWithCorrectEnvironment() throws Exception {
        builder("Pillars", "minecraft:pillars", "END");
        Path saved = migrated("minecraft:pillars");
        World result = new ManagedWorlds(plugin).loadExisting("Pillars");
        assertEquals(World.Environment.THE_END, result.getEnvironment());
        assertEquals(NamespacedKey.minecraft("pillars"), creator().key());
        assertTrue(Files.exists(saved.resolve("data/minecraft/world_gen_settings.dat")));
    }

    @Test void loadsBuilderVoidWorldWithAVoidGenerator() throws Exception {
        builder("Race", "builder:maps/race", "VOID");
        migrated("builder:maps/race");
        new ManagedWorlds(plugin).loadExisting("builder_maps/race");
        assertInstanceOf(VoidGenerator.class, creator().generator());
        assertFalse(creator().generateStructures());
        assertEquals(NamespacedKey.fromString("builder:maps/race"), creator().key());
    }

    @Test void supportsTheOldBuilderCatalogFormat() throws Exception {
        Files.writeString(builderConfig, "worlds:\n  tunnels:\n    type: NETHER\n");
        migrated("minecraft:tunnels");
        new ManagedWorlds(plugin).loadExisting("tunnels");
        assertEquals(World.Environment.NETHER, creator().environment());
    }

    @Test void doesNotGenerateAWorldForAMissingSavedBuilderEntry() throws Exception {
        builder("Missing", "minecraft:missing", "NORMAL");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ManagedWorlds(plugin).loadExisting("missing"));
        assertTrue(failure.getMessage().contains("was not found"));
        verify(server, never()).createWorld(any());
        assertFalse(Files.exists(level.resolve("dimensions/minecraft/missing")));
    }

    @Test void refusesAMigratedFolderWithoutGenerationMetadata() throws Exception {
        builder("Broken", "minecraft:broken", "NORMAL");
        Path path = level.resolve("dimensions/minecraft/broken/data/paper");
        Files.createDirectories(path);
        Files.writeString(path.resolve("metadata.dat"), "uuid");
        assertThrows(IllegalStateException.class, () -> new ManagedWorlds(plugin).loadExisting("broken"));
        verify(server, never()).createWorld(any());
    }

    @Test void requiresATypeInsteadOfGuessingTheEnvironmentOfAnUnknownMigratedWorld() throws Exception {
        migrated("minecraft:unknown");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ManagedWorlds(plugin).loadExisting("unknown"));
        assertTrue(failure.getMessage().contains("/loadworld minecraft:unknown"));
        verify(server, never()).createWorld(any());
    }

    @Test void remembersTheWorldTypeAcrossRestartWithoutBuilder() throws Exception {
        migrated("minecraft:sky");
        World loaded = world("sky", NamespacedKey.minecraft("sky"), World.Environment.THE_END, new VoidGenerator());
        online.put(loaded.getKey(), loaded);
        new ManagedWorlds(plugin).loadExisting("sky");
        online.clear();
        new ManagedWorlds(plugin).loadExisting("sky");
        assertEquals(World.Environment.THE_END, creator().environment());
        assertInstanceOf(VoidGenerator.class, creator().generator());
    }

    @Test void usesTheMainWorldNameToResolveBuiltInDimensions() throws Exception {
        migrated("minecraft:the_end");
        new ManagedWorlds(plugin).loadExisting("hub_the_end");
        assertEquals(NamespacedKey.minecraft("the_end"), creator().key());
        assertEquals(World.Environment.THE_END, creator().environment());
    }

    @Test void loadsAnUnmigratedCapitalizedWorldWithItsOriginalName() throws Exception {
        Path legacy = container.resolve("Arena");
        Files.createDirectories(legacy.resolve("DIM1/region"));
        Files.writeString(legacy.resolve("level.dat"), "saved level");
        new ManagedWorlds(plugin).loadExisting("arena");
        assertEquals("Arena", creator().name());
        assertEquals(World.Environment.THE_END, creator().environment());
        assertEquals("saved level", Files.readString(legacy.resolve("level.dat")));
    }

    @Test void doesNotChooseBetweenDuplicateOldAndMigratedWorlds() throws Exception {
        builder("Arena", "minecraft:arena", "NORMAL");
        Path legacy = container.resolve("Arena");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("level.dat"), "old");
        Path current = migrated("minecraft:arena");
        assertThrows(IllegalStateException.class, () -> new ManagedWorlds(plugin).loadExisting("arena"));
        verify(server, never()).createWorld(any());
        assertTrue(Files.exists(legacy.resolve("level.dat")));
        assertTrue(Files.exists(current));
    }

    @Test void rejectsANameThatEscapesTheWorldDirectory() {
        for (String name : List.of("../secret", "minecraft:../secret", "..:secret", "minecraft:a//b")) {
            assertThrows(IllegalArgumentException.class, () -> new ManagedWorlds(plugin).loadExisting(name), name);
        }
        verify(server, never()).createWorld(any());
    }

    @Test void returnsAWorldAlreadyLoadedThroughBuilderUsingItsAlias() throws Exception {
        builder("Pretty Name", "example:lobby", "NORMAL");
        World loaded = world("example_lobby", NamespacedKey.fromString("example:lobby"), World.Environment.NORMAL, null);
        online.put(loaded.getKey(), loaded);
        assertSame(loaded, new ManagedWorlds(plugin).loadExisting("Pretty Name"));
        verify(server, never()).createWorld(any());
    }

    @Test void reusesAnExistingCustomGeneratorWhileTheServerIsRunning() throws Exception {
        migrated("minecraft:custom");
        ChunkGenerator generator = new ChunkGenerator() { };
        World loaded = world("custom", NamespacedKey.minecraft("custom"), World.Environment.NORMAL, generator);
        online.put(loaded.getKey(), loaded);
        ManagedWorlds worlds = new ManagedWorlds(plugin);
        worlds.loadExisting("custom");
        online.clear();
        worlds.loadExisting("custom");
        assertSame(generator, creator().generator());
    }

    @Test void doesNotReplaceAnUnavailableCustomGeneratorWithVanillaTerrain() throws Exception {
        migrated("minecraft:custom");
        World loaded = world("custom", NamespacedKey.minecraft("custom"), World.Environment.NORMAL, new ChunkGenerator() { });
        online.put(loaded.getKey(), loaded);
        new ManagedWorlds(plugin).loadExisting("custom");
        online.clear();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ManagedWorlds(plugin).loadExisting("custom"));
        assertTrue(failure.getMessage().contains("custom generator"));
        verify(server, never()).createWorld(any());
    }

    @Test void reportsWhenTheServerRejectsLoadingInsteadOfReturningNull() throws Exception {
        builder("Arena", "minecraft:arena", "NORMAL");
        migrated("minecraft:arena");
        when(server.createWorld(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> new ManagedWorlds(plugin).loadExisting("arena"));
        assertFalse(Files.exists(contenderFolder.resolve("managed-worlds.yml")));
    }

    @Test void remembersWorldsLoadedByOtherPluginsAfterStartup() throws Exception {
        migrated("minecraft:late");
        World loaded = world("late", NamespacedKey.minecraft("late"), World.Environment.NETHER, null);
        new ManagedWorlds(plugin).onWorldLoad(new WorldLoadEvent(loaded));
        new ManagedWorlds(plugin).loadExisting("late");
        assertEquals(World.Environment.NETHER, creator().environment());
    }

    @Test void retriesRememberingAfterATemporarySaveFailure() throws Exception {
        Path file = contenderFolder.resolve("managed-worlds.yml");
        Files.createDirectories(file);
        ManagedWorlds worlds = new ManagedWorlds(plugin);
        World loaded = world("arena", NamespacedKey.minecraft("arena"), World.Environment.NORMAL, null);
        assertThrows(RuntimeException.class, () -> worlds.remember(loaded));
        Files.delete(file);
        worlds.remember(loaded);
        assertTrue(Files.isRegularFile(file));
        assertEquals("minecraft:arena", YamlConfiguration.loadConfiguration(file.toFile()).getMapList("worlds").getFirst().get("key"));
    }

    @Test void AMetadataSaveFailureDoesNotBreakAnAlreadyLoadedWorld() throws Exception {
        Files.createDirectories(contenderFolder.resolve("managed-worlds.yml"));
        World loaded = world("arena", NamespacedKey.minecraft("arena"), World.Environment.NORMAL, null);
        online.put(loaded.getKey(), loaded);
        assertSame(loaded, new ManagedWorlds(plugin).loadExisting("arena"));
        verify(server, never()).createWorld(any());
    }
}
