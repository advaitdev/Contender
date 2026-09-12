package me.advait.contender.manhunt;

import me.advait.contender.util.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

final class ManhuntStore {
    private final File file;
    ManhuntStore(File folder) { file = new File(folder, "manhunt.yml"); }
    ManhuntRun load() {
        var yaml = YamlConfiguration.loadConfiguration(file);
        if (!yaml.contains("event.id")) return null;
        List<ManhuntRun.Entry> entries = new ArrayList<>();
        var roster = Objects.requireNonNull(yaml.getConfigurationSection("event.players"));
        for (String key : roster.getKeys(false)) entries.add(new ManhuntRun.Entry(UUID.fromString(key), roster.getString(key + ".name"), ManhuntRun.Team.valueOf(roster.getString(key + ".team"))));
        ManhuntRun result = new ManhuntRun(UUID.fromString(yaml.getString("event.id")), yaml.getString("event.name"), yaml.getString("event.kit"), yaml.getString("event.world"), entries, yaml.getInt("event.countdown", 10));
        result.restore(ManhuntRun.State.valueOf(yaml.getString("event.state", "READY")), yaml.getStringList("event.out").stream().map(UUID::fromString).toList());
        return result;
    }
    void save(ManhuntRun run) {
        var yaml = new YamlConfiguration();
        if (run != null) {
            yaml.set("event.id", run.id().toString()); yaml.set("event.name", run.name()); yaml.set("event.kit", run.kitId());
            yaml.set("event.world", run.worldName()); yaml.set("event.state", run.state().name()); yaml.set("event.countdown", run.countdownSeconds());
            yaml.set("event.out", run.out().stream().map(UUID::toString).toList());
            for (var entry : run.entries()) { String path = "event.players." + entry.id(); yaml.set(path + ".name", entry.name()); yaml.set(path + ".team", entry.team().name()); }
        }
        YamlStorage.save(yaml, file);
    }
}
