package me.advait.contender.minigame.games;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.dialog.GameForm;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.minigame.ArenaGame;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.minigame.MinigameType;
import me.advait.contender.tab.StandingsLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

/** Free for all in one arena. Lose all your lives and you're out; the last player standing wins. */
public final class LastStandGame extends ArenaGame {
    public static final class Type implements MinigameType {
        private final Contender plugin;
        public Type(Contender plugin) { this.plugin = plugin; }
        @Override public String id() { return "last_stand"; }
        @Override public String name() { return "Last Man Standing"; }
        @Override public String description() { return "Everyone in one arena. Last one alive wins."; }
        @Override public DialogIcon icon() { return DialogIcon.APPLE; }

        @Override public void openCreate(Player director) {
            new GameForm(plugin).open(director, new GameForm.Spec("Last Man Standing", "Last Man Standing",
                    "Everyone fights at once. Pick a map that fits.", true, true, false,
                    List.of(GameForm.number("lives", DialogIcon.HEART, "Lives", 1, 5, 1, 1, null)), 2),
                    (p, result) -> {
                        Minigame game = create(result.name(), result.map(), result.kit(), result.roster(), result.values());
                        plugin.getMinigames().select(game);
                        new me.advait.contender.dialog.MinigameDialogs(plugin).control(p, game);
                    }, p -> new me.advait.contender.dialog.TournamentDialogs(plugin).formats(p));
        }

        @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
            if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with both spawns set.");
            if (kit == null) throw new IllegalArgumentException("Choose a kit.");
            return new LastStandGame(plugin, this, UUID.randomUUID(), name, roster, map, kit, GameForm.intOption(options, "lives", 1, 5, 1));
        }

        @Override public Minigame restore() {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
            if (!yaml.contains("id")) return null;
            var extra = yaml.getConfigurationSection("extra");
            ArenaMap map = extra == null ? null : plugin.getMapManager().getMap(extra.getString("map", ""));
            Kit kit = extra == null ? null : plugin.getKitManager().getKit(extra.getString("kit", ""));
            LastStandGame game = new LastStandGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()),
                    savedRoster(yaml), map, kit, extra == null ? 1 : extra.getInt("lives", 1));
            game.load(yaml);
            return game;
        }
    }

    private final int lives;
    private final Map<UUID, Integer> livesLeft = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();

    LastStandGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, ArenaMap map, Kit kit, int lives) {
        super(plugin, type, id, name, players, map, kit);
        this.lives = lives;
    }

    private void load(YamlConfiguration yaml) {
        readSaved(yaml);
        var saved = yaml.getConfigurationSection("extra.kills");
        if (saved != null) for (String key : saved.getKeys(false)) kills.put(UUID.fromString(key), saved.getInt(key));
    }

    @Override protected int minimumPlayers() { return 2; }

    @Override protected void setup(List<Player> players) {
        List<Location> ring = spawnRing(players.size());
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            deploy(player, ring.get(i));
            livesLeft.put(player.getUniqueId(), lives);
            kills.putIfAbsent(player.getUniqueId(), 0);
        }
        broadcast(Msg.text("Last one standing wins" + (lives > 1 ? ". Everyone has " + lives + " lives." : "."), DialogPalette.MUTED));
    }

    @Override protected void begin() { }

    @Override protected void killed(Player victim, Player killer) {
        UUID id = victim.getUniqueId();
        if (!isPlaying(id)) return;
        if (killer != null && !killer.equals(victim)) kills.merge(killer.getUniqueId(), 1, Integer::sum);
        int left = livesLeft.merge(id, -1, Integer::sum);
        Component message = plugin.getNameTagManager().displayName(victim).append(killer != null && !killer.equals(victim)
                ? Msg.text(" was knocked out by ", DialogPalette.MUTED).append(plugin.getNameTagManager().displayName(killer))
                : Msg.text(" was knocked out", DialogPalette.MUTED));
        if (left > 0) {
            plugin.getDeathEffect().play(victim);
            bench(victim);
            broadcast(message.append(Msg.text(" (" + left + (left == 1 ? " life" : " lives") + " left)", DialogPalette.MUTED)));
            Msg.title(victim, Msg.text("Respawning", DialogPalette.ACCENT), Msg.text(left + (left == 1 ? " life left" : " lives left"), DialogPalette.MUTED), 0, 50, 10);
            tasks.later(60, () -> respawn(victim));
            return;
        }
        participant(id).place = (int) roster().stream().filter(p -> p.status == Status.PLAYING).count();
        eliminate(victim, message);
    }

    private void respawn(Player player) {
        if (state != State.RUNNING || !isPlaying(player.getUniqueId()) || !player.isOnline()) return;
        List<Location> ring = spawnRing(Math.max(4, roster().size()));
        Location best = ring.getFirst();
        double bestDistance = -1;
        for (Location spot : ring) {
            double nearest = Double.MAX_VALUE;
            for (Player other : playing()) {
                if (other.equals(player) || other.getGameMode() == org.bukkit.GameMode.SPECTATOR || !other.getWorld().equals(spot.getWorld())) continue;
                nearest = Math.min(nearest, other.getLocation().distanceSquared(spot));
            }
            if (nearest > bestDistance) { bestDistance = nearest; best = spot; }
        }
        deploy(player, best);
    }

    @Override protected void playerLeft(Participant participant) {
        participant.place = (int) roster().stream().filter(p -> p.status == Status.PLAYING).count();
        participant.status = Status.OUT;
        checkEnd();
    }

    @Override protected void checkEnd() {
        if (state != State.RUNNING) return;
        List<Player> alive = playing();
        if (alive.size() > 1) return;
        if (alive.size() == 1) participant(alive.getFirst().getUniqueId()).place = 1;
        finish();
    }

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN).toList());
        order.sort(Comparator.comparingInt((Participant p) -> p.status == Status.PLAYING && p.place == 0 ? 0 : p.place == 0 ? 999 : p.place)
                .thenComparing(Comparator.comparingInt((Participant p) -> kills.getOrDefault(p.id, 0)).reversed()));
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (Participant p : order) {
            int k = kills.getOrDefault(p.id, 0);
            String place = p.place > 0 ? "#" + p.place : p.status == Status.PLAYING && started() ? "Alive" : "-";
            rows.add(new StandingsLayout.Row(p.id, p.name, place + "  " + k + (k == 1 ? " kill" : " kills"), p.place == 1, p.status == Status.OUT));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        super.writeExtra(section);
        section.set("lives", lives);
        kills.forEach((id, count) -> section.set("kills." + id, count));
    }
}
