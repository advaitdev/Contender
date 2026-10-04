package me.advait.contender.minigame.games;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogIcon;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.dialog.DialogPalette;
import me.advait.contender.dialog.Dialogs;
import me.advait.contender.dialog.GameForm;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.minigame.ArenaGame;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.minigame.MinigameType;
import me.advait.contender.tab.StandingsLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.*;

/** Stand on the hill in the middle of the arena alone to score a point a second. Deaths respawn you after a moment. */
public final class HillGame extends ArenaGame {
    public static final class Type implements MinigameType {
        private final Contender plugin;
        public Type(Contender plugin) { this.plugin = plugin; }
        @Override public String id() { return "king_of_the_hill"; }
        @Override public String name() { return "King of the Hill"; }
        @Override public String description() { return "Hold the middle of the arena alone to score."; }
        @Override public DialogIcon icon() { return DialogIcon.CROWN; }

        @Override public void openCreate(Player director) {
            new GameForm(plugin).open(director, new GameForm.Spec("King of the Hill", "King of the Hill",
                    "The hill is the map's hill spot, or its middle if it has none. A map's own void level wins over Void Level.", true, true, false,
                    List.of(GameForm.number("minutes", DialogIcon.CLOCK, "Length", 1, 15, 4, 1, "%s: %s min"),
                            GameForm.number("target", DialogIcon.STAR, "Points to Win", 20, 600, 90, 10, null),
                            GameForm.number("radius", DialogIcon.TARGET, "Hill Size", 2, 8, 3, 1, "%s: %s blocks"),
                            DialogInput.singleOption("damage", DialogIcon.HEART.label("Damage"), List.of(
                                    Dialogs.option("knockback", "Knockback only, no hearts or hunger lost", true),
                                    Dialogs.option("normal", "Normal damage and hunger", false))).width(300).build(),
                            DialogInput.singleOption("void", DialogIcon.STEP.label("Void Falls"), List.of(
                                    Dialogs.option("spawn", "Back to a spawn, no death", true),
                                    Dialogs.option("death", "Counts as a death", false))).width(300).build(),
                            GameForm.number("void_depth", DialogIcon.BOOTS, "Void Level", 2, 40, 8, 1, "%s: %s blocks below the hill")), 2),
                    (p, result) -> {
                        Minigame game = create(result.name(), result.map(), result.kit(), result.roster(), result.values());
                        plugin.getMinigames().select(game);
                        new me.advait.contender.dialog.MinigameDialogs(plugin).control(p, game);
                    }, p -> new me.advait.contender.dialog.TournamentDialogs(plugin).formats(p));
        }

        @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
            if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with both spawns set.");
            if (kit == null) throw new IllegalArgumentException("Choose a kit.");
            return new HillGame(plugin, this, UUID.randomUUID(), name, roster, map, kit, GameForm.intOption(options, "minutes", 1, 15, 4) * 60, GameForm.intOption(options, "target", 20, 600, 90), GameForm.intOption(options, "radius", 2, 8, 3),
                    !"death".equals(options.get("void")), GameForm.intOption(options, "void_depth", 2, 40, 8), !"normal".equals(options.get("damage")));
        }

        @Override public Minigame restore() {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
            if (!yaml.contains("id")) return null;
            var extra = yaml.getConfigurationSection("extra");
            ArenaMap map = extra == null ? null : plugin.getMapManager().getMap(extra.getString("map", ""));
            Kit kit = extra == null ? null : plugin.getKitManager().getKit(extra.getString("kit", ""));
            HillGame game = new HillGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()), savedRoster(yaml), map, kit,
                    extra == null ? 240 : extra.getInt("seconds", 240), extra == null ? 90 : extra.getInt("target", 90), extra == null ? 3 : extra.getInt("radius", 3),
                    extra == null || extra.getBoolean("void-respawn", true), extra == null ? 8 : extra.getInt("void-depth", 8),
                    extra == null || extra.getBoolean("knockback-only", true));
            game.readSaved(yaml);
            return game;
        }
    }

    private final int seconds, target, radius;
    /** Falling below the void level sends a player back to a spawn instead of killing them. */
    private final boolean voidRespawn;
    /** How far below the hill the void starts. */
    private final int voidDepth;
    /** Hits only knock people back: no hearts or hunger lost. */
    private final boolean knockbackOnly;
    private final Map<UUID, Integer> kills = new HashMap<>();
    private Location hill;
    private TextDisplay label;
    private int elapsed;

    HillGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, ArenaMap map, Kit kit,
             int seconds, int target, int radius, boolean voidRespawn, int voidDepth, boolean knockbackOnly) {
        super(plugin, type, id, name, players, map, kit);
        this.seconds = seconds;
        this.target = target;
        this.radius = radius;
        this.voidRespawn = voidRespawn;
        this.voidDepth = voidDepth;
        this.knockbackOnly = knockbackOnly;
    }

    @Override protected boolean knockbackOnly() { return knockbackOnly; }

    @Override protected int minimumPlayers() { return 2; }

    @Override protected void setup(List<Player> players) {
        // The map's hill spot, or the middle between the team spawns.
        hill = arena.layout().getHill() != null ? arena.layout().getHill() : middle();
        List<Location> ring = spawnRing(players.size());
        for (int i = 0; i < players.size(); i++) deploy(players.get(i), ring.get(i));
        Theme theme = plugin.getThemes().current();
        label = Holograms.text(hill.clone().add(0, 3.2, 0), Component.text("King of the Hill", theme.primary()), 1.6f,
                Display.Billboard.CENTER, theme.background(30), "hill");
        broadcast(Msg.text("Stand on the hill alone to score. First to " + target + " points, or the most after " + (seconds / 60) + (seconds / 60 == 1 ? " minute." : " minutes."), DialogPalette.MUTED));
    }

    @Override protected void begin() { }

    @Override protected void tick() {
        elapsed++;
        Theme theme = plugin.getThemes().current();
        List<Player> on = new ArrayList<>();
        for (Player player : playing()) {
            if (player.getGameMode() == GameMode.SPECTATOR || !player.getWorld().equals(hill.getWorld())) continue;
            Location at = player.getLocation();
            double dx = at.getX() - hill.getX(), dz = at.getZ() - hill.getZ();
            if (dx * dx + dz * dz <= radius * radius && Math.abs(at.getY() - hill.getY()) <= 3) on.add(player);
        }
        Component status;
        Color ring;
        Player king = on.size() == 1 ? on.getFirst() : null;
        if (king != null) {
            Participant participant = participant(king.getUniqueId());
            participant.score++;
            participant.value = (int) participant.score + " pts";
            status = Component.text(king.getName(), theme.primary()).append(Component.text(" holds the hill", theme.secondary()));
            ring = theme.primaryColor();
            Sounds.TICK.play(king, 1.4f);
            if (participant.score >= target) { finish(); return; }
        } else if (on.size() > 1) {
            status = Component.text("Contested", DialogPalette.WARNING);
            ring = Color.fromRGB(DialogPalette.WARNING.value());
        } else {
            status = Component.text("Nobody on the hill", theme.muted());
            ring = theme.accentColor();
        }
        int left = Math.max(0, seconds - elapsed);
        if (label != null && label.isValid()) label.text(Component.text("King of the Hill", theme.primary()).appendNewline().append(status)
                .appendNewline().append(Component.text(String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60), theme.muted())));
        for (int i = 0; i < 48; i++) {
            double angle = Math.PI * 2 * i / 48;
            hill.getWorld().spawnParticle(Particle.DUST, hill.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(ring, 1.3f));
        }
        String clock = String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
        Msg.actionBar(audience(), status.append(Msg.text("  ·  " + clock, DialogPalette.MUTED)));
        // The player on the hill sees their own progress instead (sent last, so it replaces the shared line).
        if (king != null) Msg.status(king, Component.text("You hold the hill", theme.primary()).append(Msg.text("  ·  "
                + (int) participant(king.getUniqueId()).score + " of " + target + " pts  ·  " + clock, DialogPalette.MUTED)));
        if (left == 0) finish();
    }

    /** The void starts a set distance below the hill (or at the bottom of the map, if that's higher). */
    @Override protected double voidLevel() {
        double bottom = super.voidLevel();
        // A map's own void level wins over this game's Void Level.
        return hill == null || mapSetsVoid() ? bottom : Math.max(bottom, hill.getY() - voidDepth);
    }

    /** With void respawns on, a fall isn't a death: straight back to a spawn, keeping health and items. */
    @Override protected void fell(Player player, Player knocker) {
        if (!voidRespawn) { killed(player, knocker); return; }
        if (!isPlaying(player.getUniqueId())) return;
        boolean knocked = knocker != null && !knocker.equals(player);
        if (knocked) {
            kills.merge(knocker.getUniqueId(), 1, Integer::sum);
            broadcast(plugin.getNameTagManager().displayName(player).append(Msg.text(" was knocked off by ", DialogPalette.MUTED))
                    .append(plugin.getNameTagManager().displayName(knocker)));
        }
        List<Location> ring = spawnRing(Math.max(4, roster().size()));
        player.setVelocity(new org.bukkit.util.Vector());
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.teleport(ring.get(new Random().nextInt(ring.size())));
        protect(player, 40);
        Sounds.WHOOSH.play(player);
    }

    @Override protected void killed(Player victim, Player killer) {
        if (!isPlaying(victim.getUniqueId())) return;
        if (killer != null && !killer.equals(victim)) kills.merge(killer.getUniqueId(), 1, Integer::sum);
        plugin.getDeathEffect().play(victim);
        bench(victim);
        broadcast(plugin.getNameTagManager().displayName(victim).append(killer != null && !killer.equals(victim)
                ? Msg.text(" was knocked off by ", DialogPalette.MUTED).append(plugin.getNameTagManager().displayName(killer))
                : Msg.text(" fell", DialogPalette.MUTED)));
        Msg.title(victim, Msg.text("Respawning", DialogPalette.ACCENT), Component.empty(), 0, 50, 10);
        tasks.later(60, () -> {
            if (state != State.RUNNING || !isPlaying(victim.getUniqueId()) || !victim.isOnline()) return;
            List<Location> ring = spawnRing(Math.max(4, roster().size()));
            deploy(victim, ring.get(new Random().nextInt(ring.size())));
        });
    }

    @Override protected void playerLeft(Participant participant) {
        participant.status = Status.OUT;
        checkEnd();
    }

    @Override protected void checkEnd() {
        if (state == State.RUNNING && playing().size() < 2) finish();
    }

    @Override protected void cleanup() {
        Holograms.remove(label);
        label = null;
        super.cleanup();
    }

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN).toList());
        order.sort(Comparator.comparingDouble((Participant p) -> p.score).reversed()
                .thenComparing(Comparator.comparingInt((Participant p) -> kills.getOrDefault(p.id, 0)).reversed()));
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Participant p = order.get(i);
            rows.add(new StandingsLayout.Row(p.id, p.name, (int) p.score + " pts", i == 0 && p.score > 0, p.status == Status.OUT));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        super.writeExtra(section);
        section.set("seconds", seconds);
        section.set("target", target);
        section.set("radius", radius);
        section.set("void-respawn", voidRespawn);
        section.set("void-depth", voidDepth);
        section.set("knockback-only", knockbackOnly);
    }

    // ---- Director corrections ------------------------------------------------------------------

    @Override public ScoreEdit scoreEdit(Participant participant) {
        return participant.status == Status.WITHDRAWN ? null : new ScoreEdit("Points", 0, target - 1, (int) participant.score);
    }

    @Override protected void applyScore(Participant participant, int value) {
        participant.score = value;
        participant.value = value + " pts";
    }
}
