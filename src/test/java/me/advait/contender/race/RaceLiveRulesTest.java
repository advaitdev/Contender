package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceLiveRulesTest {
    @TempDir Path folder;

    @Test void savingReturnSettingsDuringARaceAppliesThemWithoutChangingProgress() throws Exception {
        try (Fixture f = new Fixture()) {
            f.run.countdown(10);
            f.run.start(1);
            f.run.hit(f.racer, 2, 2);
            var settings = new RaceCourse("course", 15, "Finish", 14, false);
            f.manager.configure(settings);

            verify(f.session).applyReturnRules(settings);
            assertEquals(settings, f.manager.course("course"));
            assertEquals(settings, new RaceStore(folder.toFile()).load().courses().get("course"));
            assertEquals(RaceRun.State.RUNNING, f.run.state());
            assertEquals(2, f.run.racer(f.racer).checkpoint());
            verify(f.session, never()).disable();
        }
    }

    @Test void changingCheckpointOrFinishRulesDuringARaceStillRequiresFinishing() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.manager.configure(
                    new RaceCourse("course", 20, "Finish", 12, false)));
            assertThrows(IllegalStateException.class, () -> f.manager.configure(
                    new RaceCourse("course", 15, "New Finish", 12, false)));
            assertEquals(RaceCourse.defaults("course"), f.manager.course("course"));
            verify(f.session, never()).applyReturnRules(any());
        }
    }

    @Test void aReturnSettingsChangeDoesNotAlterAnotherRunningCourse() throws Exception {
        try (Fixture f = new Fixture()) {
            var settings = new RaceCourse("other", 15, "Finish", 16, false);
            f.manager.configure(settings);
            verify(f.session, never()).applyReturnRules(any());
            assertEquals(settings, new RaceStore(folder.toFile()).load().courses().get("other"));
        }
    }

    private final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final UUID racer = UUID.randomUUID();
        final RaceRun run = new RaceRun(UUID.randomUUID(), "Race", "course", 15, 0,
                List.of(new RaceRun.Racer(racer, "Racer")));
        final RaceSession session = mock(RaceSession.class);
        final RaceManager manager;

        Fixture() throws Exception {
            Contender plugin = mock(Contender.class);
            when(plugin.getName()).thenReturn("Contender");
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            MapManager maps = mock(MapManager.class);
            when(plugin.getMapManager()).thenReturn(maps);
            ArenaMap map = new ArenaMap("course");
            map.setRollbackRegion(0, 0, 0, 30, 100, 30);
            when(maps.getMap(anyString())).thenReturn(map);
            manager = new RaceManager(plugin);
            set("current", run);
            set("session", session);
        }

        private void set(String name, Object value) throws Exception {
            var field = RaceManager.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(manager, value);
        }

        @Override public void close() { bukkit.close(); }
    }
}
