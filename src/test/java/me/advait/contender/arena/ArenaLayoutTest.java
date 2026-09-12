package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaLayoutTest {
    private ArenaMap template() {
        ArenaMap map = new ArenaMap("forest");
        map.setDisplayName("Forest");
        map.setWorldName("source");
        map.setRollbackRegion(-100, 40, -80, -51, 100, -21);
        map.setTeam1Spawn(-99.5, 41, -79.5, 90, 10);
        map.setTeam2Spawn(-52, 41, -22, -90, -5);
        map.setSpectatorSpawn(-75, 70, -50, 45, 20);
        return map;
    }
    @Test void copiesKeepRelativeSpawnsAndHaveSeparateBounds() {
        ArenaMap template = template();
        List<ArenaInstance> instances = new ArrayList<>();
        for (int i = 0; i < 20; i++) instances.add(ArenaLayout.place(template, "arenas", i, 1024, -64, 320));
        for (var instance : instances) {
            var map = instance.map();
            assertEquals("Forest", map.getDisplayName());
            assertEquals("arenas", map.getWorldName());
            assertEquals(0.5, map.getTeam1Point().x() - map.getBounds().minX());
            assertEquals(0.5, map.getTeam1Point().z() - map.getBounds().minZ());
            assertEquals(90, map.getTeam1Point().yaw());
            assertEquals(10, map.getTeam1Point().pitch());
            assertEquals(70, map.getSpectatorPoint().y());
            for (var other : instances) if (instance != other) {
                assertFalse(instance.cell().containsColumn(other.map().getTeam1Point().x(), other.map().getTeam1Point().z()));
            }
        }
        assertEquals(-100, template.getBounds().minX());
        var nextMap = ArenaLayout.place(template, "arenas", 100, 1024, -64, 320);
        assertEquals(1024, nextMap.cell().minZ());
    }
    @Test void rejectsOversizedRegionsAndMissingSpawns() {
        ArenaMap map = template();
        assertThrows(IllegalArgumentException.class, () -> ArenaLayout.place(map, "arenas", 0, 100, -64, 320));
        map.setRollbackRegion(0, -65, 0, 10, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> ArenaLayout.place(map, "arenas", 0, 1024, -64, 320));
        assertThrows(IllegalArgumentException.class, () -> ArenaLayout.place(new ArenaMap("empty"), "arenas", 0, 1024, -64, 320));
    }
    @Test void reservationsSurviveResetsAndStaleReleasesCannotFreeAnotherMatch() {
        ArenaInstance instance = ArenaLayout.place(template(), "arenas", 0, 1024, -64, 320);
        assertNull(instance.acquire());
        instance.restored(instance.beginReset());
        ArenaLease first = instance.acquire();
        assertNotNull(first); assertNull(instance.acquire());
        long reset = instance.beginReset();
        assertNull(instance.acquire());
        instance.restored(reset);
        assertEquals(ArenaInstance.Status.IN_USE, instance.status());
        instance.release(first);
        ArenaLease second = instance.acquire();
        assertNotNull(second);
        instance.release(first);
        assertTrue(instance.owns(second));
        assertNull(instance.acquire());
        instance.failed();
        instance.release(second);
        assertNull(instance.acquire());
    }
}
