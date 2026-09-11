package me.advait.contender.map;

import me.advait.contender.Contender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapManagerTest {
    @TempDir Path directory;
    @Test void namedPoolsAndRelativeSpawnsSurviveReloadWithoutOverlappingAllocations() throws Exception {
        Files.writeString(directory.resolve("maps.yml"), "maps: {}\n");
        Contender plugin = mock(Contender.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        MapManager manager = new MapManager(plugin);
        ArenaMap forest = new ArenaMap("forest");
        forest.setDisplayName("Forest at night");
        forest.setWorldName("source");
        forest.setRollbackRegion(-20, 40, -10, 20, 100, 30);
        forest.setTeam1Spawn(-19.5, 41, -9.5, 90, 5);
        forest.setTeam2Spawn(19.5, 41, 29.5, -90, -5);
        forest.setSchematic("forest-test.schem");
        manager.save(forest);
        assertEquals(0, manager.allocateSlots(forest));
        ArenaMap desert = new ArenaMap("desert");
        manager.save(desert);
        assertEquals(100, manager.allocateSlots(desert));
        forest.setCopies(100);
        manager.save(forest);
        MapManager loaded = new MapManager(plugin);
        ArenaMap restored = loaded.getMap("forest");
        assertEquals("Forest at night", restored.getDisplayName());
        assertEquals(forest.getTeam1Point(), restored.getTeam1Point());
        assertEquals(forest.getBounds(), restored.getBounds());
        assertEquals(100, restored.getCopies());
        assertEquals(0, restored.getFirstSlot());
        assertEquals(100, loaded.getMap("desert").getFirstSlot());
        assertEquals(20, loaded.getMap("desert").getCopies());
        assertEquals("forest-test.schem", restored.getSchematic());
    }
}
