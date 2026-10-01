package me.advait.contender.minigame.games;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;

/**
 * Winner stays on: the champion fights challengers one at a time, the loser goes to the back of the queue,
 * and everyone else watches. Most wins takes it; the longest streak breaks ties.
 */
public final class GauntletGame extends ArenaGame {
    public static final class Type implements MinigameType {
        private final Contender plugin;
        public Type(Contender plugin) { this.plugin = plugin; }
        @Override public String id() { return "winner_stays_on"; }
        @Override public String name() { return "Winner Stays On"; }
        @Override public String description() { return "One fight at a time. The winner keeps fighting until someone beats them."; }
        @Override public DialogIcon icon() { return DialogIcon.TOURNAMENT; }

        @Override public void openCreate(Player director) {
            new GameForm(plugin).open(director, new GameForm.Spec("Winner Stays On", "Winner Stays On",
                    "Fights happen one at a time while everyone else watches. One round per fight.", true, true, false,
                    List.of(GameForm.number("fights", DialogIcon.DUEL, "Total Fights", 2, 100, 12, 1, null)), 2),
                    (p, result) -> {
                        Minigame game = create(result.name(), result.map(), result.kit(), result.roster(), result.values());
                        plugin.getMinigames().select(game);
                        new me.advait.contender.dialog.MinigameDialogs(plugin).control(p, game);
                    }, p -> new me.advait.contender.dialog.TournamentDialogs(plugin).formats(p));
        }

        @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
            if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with both spawns set.");
            if (kit == null) throw new IllegalArgumentException("Choose a kit.");
            return new GauntletGame(plugin, this, UUID.randomUUID(), name, roster, map, kit, GameForm.intOption(options, "fights", 2, 100, 12));
        }

        @Override public Minigame restore() {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
            if (!yaml.contains("id")) return null;
            var extra = yaml.getConfigurationSection("extra");
            ArenaMap map = extra == null ? null : plugin.getMapManager().getMap(extra.getString("map", ""));
            Kit kit = extra == null ? null : plugin.getKitManager().getKit(extra.getString("kit", ""));
            GauntletGame game = new GauntletGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()), savedRoster(yaml),
                    map, kit, extra == null ? 12 : extra.getInt("fights", 12));
            game.readSaved(yaml);
            var streaks = yaml.getConfigurationSection("extra.best-streak");
            if (streaks != null) for (String key : streaks.getKeys(false)) game.best.put(UUID.fromString(key), streaks.getInt(key));
            return game;
        }
    }

    private final int totalFights;
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, Integer> best = new HashMap<>();
    private UUID champion, challenger;
    private boolean live, frozen;
    private org.bukkit.scheduler.BukkitTask fightCountdown;
    private int fought, streak;

    GauntletGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, ArenaMap map, Kit kit, int totalFights) {
        super(plugin, type, id, name, players, map, kit);
        this.totalFights = totalFights;
    }

    @Override protected int minimumPlayers() { return 2; }
    @Override protected int countdownSeconds() { return 5; }

    @Override protected boolean fighting(Player player) {
        UUID id = player.getUniqueId();
        return state == State.RUNNING && live && !frozen && (id.equals(champion) || id.equals(challenger)) && player.getGameMode() != GameMode.SPECTATOR;
    }

    @Override protected void setup(List<Player> players) {
        List<UUID> order = new ArrayList<>(players.stream().map(Player::getUniqueId).toList());
        Collections.shuffle(order);
        queue.addAll(order);
        for (Player player : players) {
            bench(player);
            player.teleport(watchSpot());
        }
        broadcast(Msg.text("Order: " + String.join(", ", order.stream().map(id -> participant(id).name).toList()), DialogPalette.MUTED));
    }

    @Override protected void begin() { nextFight(); }

    private boolean ready(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && !player.isDead() && isPlaying(id);
    }

    private void nextFight() {
        if (state != State.RUNNING) return;
        if (fought >= totalFights) { finish(); return; }
        queue.removeIf(id -> !ready(id));
        if (champion != null && !ready(champion)) { champion = null; streak = 0; }
        if (champion == null) champion = queue.pollFirst();
        challenger = queue.pollFirst();
        if (champion == null || challenger == null) { finish(); return; }
        Player first = Bukkit.getPlayer(champion), second = Bukkit.getPlayer(challenger);
        deploy(first, arena.layout().getTeam1Spawn());
        deploy(second, arena.layout().getTeam2Spawn());
        var theme = plugin.getThemes().current();
        Component title = Component.text(first.getName(), theme.primary()).append(Component.text(" vs ", theme.muted())).append(Component.text(second.getName(), theme.secondary()));
        Component subtitle = Msg.text(streak > 0 ? first.getName() + " is on a " + streak + "-win streak" : "Fight " + (fought + 1) + " of " + totalFights, DialogPalette.MUTED);
        Msg.title(audience(), title, subtitle, 4, 40, 6);
        frozen = true;
        live = true;
        fightCountdown = countdown(3, () -> {
            fightCountdown = null;
            if (!live || champion == null || challenger == null) return;
            frozen = false;
            Msg.title(Set.of(champion, challenger), Component.text("Fight!", theme.primary()), Component.empty(), 0, 14, 6);
            Sounds.GO.play(audience());
        });
    }

    @Override protected void killed(Player victim, Player killer) {
        UUID id = victim.getUniqueId();
        if (!live || !(id.equals(champion) || id.equals(challenger))) return;
        UUID winnerId = id.equals(champion) ? challenger : champion;
        conclude(winnerId, id);
    }

    private void conclude(UUID winnerId, UUID loserId) {
        live = false;
        frozen = false;
        if (fightCountdown != null) { tasks.cancel(fightCountdown); fightCountdown = null; }
        Participant winner = participant(winnerId);
        winner.score++;
        streak = winnerId.equals(champion) ? streak + 1 : 1;
        best.merge(winnerId, streak, Math::max);
        winner.value = (int) winner.score + " wins";
        fought++;
        Player loser = Bukkit.getPlayer(loserId);
        if (loser != null && plugin.getRegistry().owner(loserId) == this) {
            plugin.getDeathEffect().play(loser);
            bench(loser);
        }
        if (isPlaying(loserId)) queue.addLast(loserId);
        champion = winnerId;
        challenger = null;
        Player won = Bukkit.getPlayer(winnerId);
        var theme = plugin.getThemes().current();
        broadcast(Component.text(winner.name, theme.primary()).append(Msg.text(" wins" + (streak > 1 ? " (" + streak + " in a row)" : "") + ".", DialogPalette.TEXT)));
        if (won != null) {
            Sounds.ROUND_WIN.play(won);
            Msg.title(won, Component.text("You win", theme.primary()), Msg.text(streak > 1 ? streak + " in a row" : "Stay on for the next fight", DialogPalette.MUTED), 2, 30, 6);
        }
        plugin.getStages().refreshDisplays();
        tasks.later(60, this::nextFight);
    }

    @Override public void withdraw(UUID player) {
        boolean inFight = live && (player.equals(champion) || player.equals(challenger));
        UUID other = player.equals(champion) ? challenger : champion;
        super.withdraw(player);
        queue.remove(player);
        if (state != State.RUNNING) return;
        // The fight can't go on without them: the other fighter wins it.
        if (inFight && other != null) conclude(other, player);
        else if (player.equals(champion)) { champion = null; streak = 0; }
    }

    @Override protected void playerLeft(Participant participant) {
        UUID id = participant.id;
        queue.remove(id);
        if (live && (id.equals(champion) || id.equals(challenger))) {
            UUID other = id.equals(champion) ? challenger : champion;
            participant.status = Status.OUT;
            conclude(other, id);
            return;
        }
        if (id.equals(champion)) { champion = null; streak = 0; }
        participant.status = Status.OUT;
        checkEnd();
    }

    @Override protected void playerReturned(Player player, Participant participant) {
        super.playerReturned(player, participant);
        if (participant.status == Status.PLAYING && !queue.contains(player.getUniqueId())
                && !player.getUniqueId().equals(champion) && !player.getUniqueId().equals(challenger)) queue.addLast(player.getUniqueId());
    }

    @Override protected void checkEnd() {
        if (state == State.RUNNING && playing().size() < 2) finish();
    }

    /** The two fighters stand still during the countdown before each fight. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozen(PlayerMoveEvent event) {
        if (!frozen || event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) return;
        UUID id = event.getPlayer().getUniqueId();
        if (!id.equals(champion) && !id.equals(challenger)) return;
        Location held = event.getFrom().clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
    }

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN).toList());
        order.sort(Comparator.comparingDouble((Participant p) -> p.score).reversed()
                .thenComparing(Comparator.comparingInt((Participant p) -> best.getOrDefault(p.id, 0)).reversed()));
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (Participant p : order) {
            int wins = (int) p.score, streakBest = best.getOrDefault(p.id, 0);
            String value = wins + (wins == 1 ? " win" : " wins") + (streakBest > 1 ? "  best " + streakBest : "");
            rows.add(new StandingsLayout.Row(p.id, p.name, value, p.id.equals(champion) && started(), p.status == Status.OUT));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        super.writeExtra(section);
        section.set("fights", totalFights);
        best.forEach((id, value) -> section.set("best-streak." + id, value));
    }
}
