package me.advait.contender.race;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RaceStoreTest {
    @TempDir Path folder;
    @Test void olderDefaultHeightIsRaisedWhileCustomHeightsStayUnchanged() throws Exception {
        java.nio.file.Files.writeString(folder.resolve("mace-race.yml"), """
                courses:
                  old-default:
                    return-height: 3
                  custom:
                    return-height: 5
                  missing:
                    max-advance: 15
                """);
        var store = new RaceStore(folder.toFile());
        var saved = store.load();
        assertEquals(12, saved.courses().get("old-default").returnHeight());
        assertEquals(12, saved.courses().get("missing").returnHeight());
        assertEquals(5, saved.courses().get("custom").returnHeight());
        assertEquals(12, RaceCourse.defaults("new").returnHeight());
        store.save(saved.courses(), null, false);
        assertEquals(saved.courses(), store.load().courses());
    }
    @Test void versionTwoDefaultsUpgradeOnceAndNewCustomHeightsStaySaved() throws Exception {
        java.nio.file.Files.writeString(folder.resolve("mace-race.yml"), """
                version: 2
                courses:
                  three:
                    return-height: 3
                  six:
                    return-height: 6
                  custom:
                    return-height: 9
                """);
        var store = new RaceStore(folder.toFile());
        var courses = store.load().courses();
        assertEquals(12, courses.get("three").returnHeight());
        assertEquals(12, courses.get("six").returnHeight());
        assertEquals(9, courses.get("custom").returnHeight());
        store.save(Map.of("three", new RaceCourse("three", 15, "Finish", 3),
                "six", new RaceCourse("six", 15, "Finish", 6)), null, false);
        assertEquals(3, store.load().courses().get("three").returnHeight());
        assertEquals(6, store.load().courses().get("six").returnHeight());
    }
    @Test void choosingThreeBlocksAfterTheUpgradeSurvivesReload() {
        var store = new RaceStore(folder.toFile());
        var course = new RaceCourse("custom", 15, "Finish", 3);
        store.save(Map.of("custom", course), null, false);
        assertEquals(course, store.load().courses().get("custom"));
    }
    @Test void restartKeepsFinishTimesAndMarksAnActiveRaceInterrupted() {
        var a = new RaceRun.Racer(UUID.randomUUID(), "Alice"); var b = new RaceRun.Racer(UUID.randomUUID(), "Bob");
        var run = new RaceRun(UUID.randomUUID(), "Mace", "course", 15, 1200, List.of(a, b), "custom_race_kit");
        run.countdown(20); run.start(100); run.hit(a.id(), 15, 200); run.hit(a.id(), 21, 300); run.hit(b.id(), 2, 400);
        var store = new RaceStore(folder.toFile()); var course = new RaceCourse("course", 10, "Finish Line", 4);
        store.save(Map.of("course", course), run, true);
        var saved = store.load(); assertTrue(saved.selected()); assertEquals(course, saved.courses().get("course"));
        var restored = saved.run(); assertEquals(run.id(), restored.id()); assertEquals(RaceRun.State.INTERRUPTED, restored.state());
        assertEquals(200, restored.racer(a.id()).finishNanos()); assertEquals(1, restored.racer(a.id()).place());
        assertTrue(restored.racer(b.id()).withdrawn()); assertEquals(2, restored.racer(b.id()).checkpoint());
        assertEquals(1200, restored.timeLimitSeconds());
        assertEquals("custom_race_kit", restored.kitId());
    }
    @Test void preparedOfflineRosterSurvivesRestartWithoutBeingCancelled() {
        var racer = new RaceRun.Racer(UUID.randomUUID(), "OfflinePlayer");
        var run = new RaceRun(UUID.randomUUID(), "Race", "map", 15, 0, List.of(racer));
        var store = new RaceStore(folder.toFile()); store.save(Map.of(), run, false);
        var saved = store.load(); assertFalse(saved.selected()); assertEquals(RaceRun.State.READY, saved.run().state());
        assertEquals(racer.name(), saved.run().racers().getFirst().name()); assertFalse(saved.run().racers().getFirst().withdrawn());
    }
    @Test void racesSavedBeforeKitSelectionKeepTheBuiltInLoadout() throws Exception {
        var run = new RaceRun(UUID.randomUUID(), "Race", "map", 15, 0,
                List.of(new RaceRun.Racer(UUID.randomUUID(), "Alice")));
        var store = new RaceStore(folder.toFile());
        store.save(Map.of(), run, true);
        var file = folder.resolve("mace-race.yml").toFile();
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        yaml.set("race.kit", null); yaml.save(file);

        assertEquals("", store.load().run().kitId());
        assertEquals("", run.kitId());
    }
    @Test void groundReturnSettingSurvivesReloadForEachCourse() {
        var store = new RaceStore(folder.toFile());
        var enabled = RaceCourse.defaults("airborne");
        var disabled = new RaceCourse("platforms", 15, "Finish", 3, false);
        store.save(Map.of(enabled.mapId(), enabled, disabled.mapId(), disabled), null, false);

        var courses = store.load().courses();
        assertTrue(courses.get("airborne").returnOnGround());
        assertFalse(courses.get("platforms").returnOnGround());
        assertEquals(disabled, courses.get("platforms"));
    }
    @Test void existingCoursesEnableGroundReturnWhenTheSettingIsMissing() throws Exception {
        java.nio.file.Files.writeString(folder.resolve("mace-race.yml"), """
                courses:
                  old-course:
                    max-advance: 12
                    finish-name: Finish Line
                    return-height: 5
                """);

        var course = new RaceStore(folder.toFile()).load().courses().get("old-course");
        assertTrue(course.returnOnGround());
        assertEquals(12, course.maxAdvance());
        assertEquals("Finish Line", course.finishName());
        assertEquals(5, course.returnHeight());
    }
}
