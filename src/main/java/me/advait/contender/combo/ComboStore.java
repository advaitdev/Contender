package me.advait.contender.combo;

import java.io.File;
import java.util.*;
import me.advait.contender.util.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;

final class ComboStore {
    private final File file;
    ComboStore(File folder) { file = new File(folder, "combo.yml"); }
    ComboRun load() {
        var yaml = YamlConfiguration.loadConfiguration(file);
        if (!yaml.contains("event.id")) return null;
        List<ComboRun.Entry> entries = new ArrayList<>();
        var roster = yaml.getConfigurationSection("event.players");
        if (roster != null) for (String key : roster.getKeys(false)) {
            var row = roster.getConfigurationSection(key);
            var entry = new ComboRun.Entry(UUID.fromString(key), row.getString("name", key));
            Integer score = row.contains("score") ? row.getInt("score") : null;
            if (score != null && score < 0) throw new IllegalArgumentException("Invalid saved combo score");
            entry.restore(score, row.getBoolean("cleared"), row.getBoolean("withdrawn")); entries.add(entry);
        }
        var run = new ComboRun(UUID.fromString(yaml.getString("event.id")), yaml.getString("event.name"), yaml.getString("event.map"),
                yaml.getString("event.kit"), ComboDifficulty.valueOf(yaml.getString("event.difficulty", "NORMAL")), yaml.getInt("event.grace-hits", 5), entries);
        run.restore(ComboRun.State.valueOf(yaml.getString("event.state", "READY"))); return run;
    }
    void save(ComboRun run) {
        var yaml = new YamlConfiguration();
        if (run != null) {
            yaml.set("event.id", run.id().toString()); yaml.set("event.name", run.name()); yaml.set("event.map", run.mapId());
            yaml.set("event.kit", run.kitId()); yaml.set("event.difficulty", run.difficulty().name());
            yaml.set("event.grace-hits", run.graceHits()); yaml.set("event.state", run.state().name());
            for (var entry : run.entries()) {
                String path = "event.players." + entry.id(); yaml.set(path + ".name", entry.name()); yaml.set(path + ".score", entry.score());
                yaml.set(path + ".cleared", entry.cleared()); yaml.set(path + ".withdrawn", entry.withdrawn());
            }
        }
        YamlStorage.save(yaml, file);
    }
}
