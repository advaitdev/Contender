package me.advait.contender.race;

import me.advait.contender.util.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

final class RaceStore {
    private static final int VERSION = 3;
    private final File file;
    RaceStore(File folder) { file = new File(folder, "mace-race.yml"); }
    record Saved(Map<String, RaceCourse> courses, RaceRun run, boolean selected) { }
    Saved load() {
        var yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, RaceCourse> courses = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("courses");
        if (section != null) for (String id : section.getKeys(false)) {
            var c = section.getConfigurationSection(id);
            double height = c.getDouble("return-height", RaceCourse.DEFAULT_RETURN_HEIGHT);
            // Upgrade the old default once; heights explicitly saved in this format stay unchanged.
            if (yaml.getInt("version", 1) < VERSION && (height == 3 || height == 6)) height = RaceCourse.DEFAULT_RETURN_HEIGHT;
            courses.put(id, new RaceCourse(id, c.getInt("max-advance", 15), c.getString("finish-name", "Finish"),
                    height, c.getBoolean("return-on-ground", true)));
        }
        RaceRun run = null;
        var race = yaml.getConfigurationSection("race");
        if (race != null) {
            List<RaceRun.Racer> racers = new ArrayList<>();
            var roster = race.getConfigurationSection("racers");
            if (roster != null) for (String id : roster.getKeys(false)) {
                var r = roster.getConfigurationSection(id);
                var racer = new RaceRun.Racer(UUID.fromString(id), r.getString("name", id));
                racer.restore(r.getInt("checkpoint"), r.getLong("finish-nanos", -1), r.getInt("place"), r.getBoolean("withdrawn"));
                racers.add(racer);
            }
            run = new RaceRun(UUID.fromString(race.getString("id")), race.getString("name"), race.getString("map"),
                    race.getInt("max-advance", 15), race.getInt("time-limit"), racers, race.getString("kit", ""));
            run.restore(RaceRun.State.valueOf(race.getString("state")), race.getInt("checkpoints"));
        }
        return new Saved(courses, run, yaml.getBoolean("selected"));
    }
    void save(Map<String, RaceCourse> courses, RaceRun run, boolean selected) {
        var yaml = new YamlConfiguration();
        yaml.set("version", VERSION);
        yaml.set("selected", selected);
        courses.forEach((id, c) -> {
            String key = "courses." + id;
            yaml.set(key + ".max-advance", c.maxAdvance()); yaml.set(key + ".finish-name", c.finishName()); yaml.set(key + ".return-height", c.returnHeight());
            yaml.set(key + ".return-on-ground", c.returnOnGround());
        });
        if (run != null) {
            yaml.set("race.id", run.id().toString()); yaml.set("race.name", run.name()); yaml.set("race.map", run.mapId());
            yaml.set("race.state", run.state().name()); yaml.set("race.max-advance", run.maxAdvance());
            yaml.set("race.kit", run.kitId());
            yaml.set("race.time-limit", run.timeLimitSeconds()); yaml.set("race.checkpoints", run.checkpointCount());
            for (var racer : run.racers()) {
                String key = "race.racers." + racer.id();
                yaml.set(key + ".name", racer.name()); yaml.set(key + ".checkpoint", racer.checkpoint());
                yaml.set(key + ".finish-nanos", racer.finishNanos()); yaml.set(key + ".place", racer.place()); yaml.set(key + ".withdrawn", racer.withdrawn());
            }
        }
        YamlStorage.save(yaml, file);
    }
}
