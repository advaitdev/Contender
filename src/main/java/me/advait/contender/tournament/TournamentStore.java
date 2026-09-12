package me.advait.contender.tournament;

import me.advait.contender.duel.DuelResult;
import me.advait.contender.util.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

public final class TournamentStore {
    private final File file;
    public TournamentStore(File file) { this.file = file; }
    public void save(Tournament tournament) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", tournament.id().toString());
        yaml.set("name", tournament.name());
        yaml.set("map", tournament.mapId());
        yaml.set("kit", tournament.kitId());
        yaml.set("teams", tournament.teams());
        yaml.set("wait-for-round", tournament.waitForRound());
        yaml.set("sorting-seconds", tournament.sortingSeconds());
        yaml.set("max-parallel", tournament.maxParallel());
        yaml.set("bracket-rounds", tournament.rounds());
        yaml.set("cancelled", tournament.isCancelled());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (TournamentEntry entry : tournament.entries()) {
            Map<String, String> names = new LinkedHashMap<>();
            entry.playerNames().forEach((id, name) -> names.put(id.toString(), name));
            entries.add(Map.of("name", entry.name(), "players", entry.players().stream().map(UUID::toString).toList(), "player-names", names));
        }
        yaml.set("entries", entries);
        for (TournamentMatch match : tournament.matches()) {
            String path = "matches." + match.number();
            yaml.set(path + ".best-of", match.bestOf());
            if (match.result() == null) continue;
            yaml.set(path + ".reason", match.result().reason().name());
            yaml.set(path + ".score1", match.result().team1Score());
            yaml.set(path + ".score2", match.result().team2Score());
            yaml.set(path + ".winner", match.result().winner());
        }
        YamlStorage.save(yaml, file);
    }
    public Tournament load() {
        if (!file.exists()) return null;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<TournamentEntry> entries = new ArrayList<>();
        for (Map<?, ?> entry : yaml.getMapList("entries")) {
            List<UUID> players = ((List<?>) entry.get("players")).stream().map(value -> UUID.fromString(value.toString())).toList();
            Map<UUID, String> names = new LinkedHashMap<>();
            if (entry.get("player-names") instanceof Map<?, ?> storedNames) {
                storedNames.forEach((id, name) -> names.put(UUID.fromString(id.toString()), name.toString()));
            }
            entries.add(new TournamentEntry((String) entry.get("name"), players, names));
        }
        Tournament tournament = new Tournament(UUID.fromString(yaml.getString("id")), yaml.getString("name"),
                yaml.getString("map"), yaml.getString("kit"), entries, yaml.getBoolean("teams"),
                yaml.getBoolean("wait-for-round"), 3, yaml.getInt("sorting-seconds", 10), yaml.getInt("max-parallel", 20),
                yaml.getInt("bracket-rounds", RoundRobinSchedule.fullRounds(entries.size())));
        for (TournamentMatch match : tournament.matches()) {
            String path = "matches." + match.number();
            match.setBestOf(yaml.getInt(path + ".best-of", 3));
            if (yaml.contains(path + ".reason")) {
                match.finish(new DuelResult(DuelResult.Reason.valueOf(yaml.getString(path + ".reason")),
                        yaml.getInt(path + ".score1"), yaml.getInt(path + ".score2"),
                        yaml.contains(path + ".winner") ? yaml.getInt(path + ".winner") : null));
            }
        }
        if (yaml.getBoolean("cancelled")) tournament.cancel();
        // Resume is explicit after a restart; unfinished matches return to the queue.
        return tournament;
    }
}
