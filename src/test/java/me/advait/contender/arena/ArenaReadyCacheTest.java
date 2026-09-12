package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArenaReadyCacheTest {
    @TempDir Path directory;
    private Path data;
    private Path worldFolder;
    private World world;
    private ArenaMap template;
    private ArenaInstance instance;

    @BeforeEach void setUp() throws Exception {
        data = Files.createDirectories(directory.resolve("plugin"));
        worldFolder = Files.createDirectories(directory.resolve("arenas"));
        Files.createDirectories(data.resolve("maps"));
        Files.writeString(data.resolve("maps/course-original.schem"), "saved template");
        world = mock(World.class);
        when(world.getName()).thenReturn("contender_arenas");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        template = new ArenaMap("course");
        template.setWorldName("source");
        template.setRollbackRegion(0, 0, 0, 31, 10, 31);
        template.setTeam1Spawn(2, 2, 2, 0, 0);
        template.setTeam2Spawn(22, 2, 22, 0, 0);
        template.setSchematic("course-original.schem");
        instance = ArenaLayout.place(template, world.getName(), 0, 1024, -64, 320);
        writeChunks(worldFolder, instance.map().getBounds());
    }

    @Test void cleanShutdownRecordIsReusableOnlyOnce() throws Exception {
        ArenaReadyCache cache = savedCache();
        assertTrue(cache.reusable(template, instance, world, 1024));
        assertFalse(Files.exists(data.resolve("arena-ready.yml")));

        ArenaReadyCache afterCrash = new ArenaReadyCache(data);
        afterCrash.consume();
        assertFalse(afterCrash.reusable(template, instance, world, 1024));
    }

    @Test void changedSchematicFileOrRevisionRequiresPaste() throws Exception {
        ArenaReadyCache cache = savedCache();
        Path schematic = data.resolve("maps/course-original.schem");
        FileTime original = Files.getLastModifiedTime(schematic);
        Files.setLastModifiedTime(schematic, FileTime.fromMillis(original.toMillis() + 2000));
        assertFalse(cache.reusable(template, instance, world, 1024));
        Files.setLastModifiedTime(schematic, original);
        Files.writeString(data.resolve("maps/course-new.schem"), "saved template");
        template.setSchematic("course-new.schem");
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void differentWorldIdentityOrLayoutRequiresPaste() throws Exception {
        ArenaReadyCache cache = savedCache();
        assertFalse(cache.reusable(template, instance, world, 2048));
        assertFalse(cache.reusable(template, ArenaLayout.place(template, world.getName(), 1, 1024, -64, 320), world, 1024));
        when(world.getUID()).thenReturn(UUID.randomUUID());
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void sourceBoundsChangeRequiresPasteButNameAndSpawnsDoNot() throws Exception {
        ArenaReadyCache cache = savedCache();
        template.setDisplayName("New display name");
        template.setTeam1Spawn(4, 2, 4, 90, 0);
        assertTrue(cache.reusable(template, instance, world, 1024));
        template.setRollbackRegion(0, 0, 0, 31, 11, 31);
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void missingOrTruncatedChunksCannotBeReused() throws Exception {
        ArenaReadyCache cache = savedCache();
        Path region = worldFolder.resolve("region/r.0.0.mca");
        Files.write(region, new byte[8192]);
        assertFalse(cache.reusable(template, instance, world, 1024));
        Files.delete(region);
        assertFalse(cache.reusable(template, instance, world, 1024));
        Files.write(region, new byte[20]);
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void oneMissingChunkInvalidatesTheCopy() throws Exception {
        ArenaReadyCache cache = savedCache();
        Path region = worldFolder.resolve("region/r.0.0.mca");
        byte[] bytes = Files.readAllBytes(region);
        ByteBuffer.wrap(bytes).putInt(4 * (2 + 32 * 2), 0);
        Files.write(region, bytes);
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void negativeCoordinatesAndRegionBoundariesAreChecked() throws Exception {
        BlockBounds bounds = new BlockBounds(-17, 0, -1, 16, 10, 16);
        writeChunks(worldFolder, bounds);
        assertTrue(ArenaReadyCache.hasChunks(worldFolder, bounds));
        Files.delete(worldFolder.resolve("region/r.-1.-1.mca"));
        assertFalse(ArenaReadyCache.hasChunks(worldFolder, bounds));
    }

    @Test void malformedRecordIsConsumedWithoutBeingTrusted() throws Exception {
        Files.writeString(data.resolve("arena-ready.yml"), "copies: [ invalid");
        ArenaReadyCache cache = new ArenaReadyCache(data);
        assertThrows(java.io.IOException.class, cache::consume);
        assertFalse(Files.exists(data.resolve("arena-ready.yml")));
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void explicitInvalidationPreventsReuse() throws Exception {
        ArenaReadyCache cache = savedCache();
        cache.forget("other");
        assertTrue(cache.reusable(template, instance, world, 1024));
        cache.forget("course");
        assertFalse(cache.reusable(template, instance, world, 1024));
        cache = savedCache();
        cache.forget();
        assertFalse(cache.reusable(template, instance, world, 1024));
    }

    @Test void onlyUnreservedSuccessfullyRestoredCopiesCanBeCached() {
        assertFalse(instance.canCache());
        instance.reuseSavedCopy();
        assertTrue(instance.canCache());
        ArenaLease lease = instance.acquire();
        assertFalse(instance.canCache());
        instance.release(lease);
        assertFalse(instance.canCache(), "Releasing a used copy without restoring it cannot make it clean");
        lease = instance.acquire();
        instance.restored(instance.beginReset());
        assertFalse(instance.canCache(), "Even a freshly reset reservation is excluded");
        instance.release(lease);
        assertTrue(instance.canCache());
        instance.failed();
        assertFalse(instance.canCache());
    }

    private ArenaReadyCache savedCache() throws Exception {
        ArenaReadyCache cache = new ArenaReadyCache(data);
        cache.write(List.of(cache.entry(template, instance, world, 1024)));
        cache.consume();
        return cache;
    }

    /** Minimal Anvil headers are enough to test the disk-presence check, without loading a server. */
    static void writeChunks(Path folder, BlockBounds bounds) throws Exception {
        Files.createDirectories(folder.resolve("region"));
        java.util.Map<Path, byte[]> regions = new java.util.HashMap<>();
        for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++) {
            for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++) {
                Path region = folder.resolve("region/r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
                byte[] bytes = regions.get(region);
                if (bytes == null) {
                    bytes = Files.exists(region) ? Files.readAllBytes(region) : new byte[12288];
                    regions.put(region, bytes);
                }
                ByteBuffer.wrap(bytes).putInt(4 * (Math.floorMod(x, 32) + 32 * Math.floorMod(z, 32)), (2 << 8) | 1);
            }
        }
        for (var entry : regions.entrySet()) Files.write(entry.getKey(), entry.getValue());
    }
}
