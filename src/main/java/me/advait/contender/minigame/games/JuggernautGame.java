package me.advait.contender.minigame.games;

import me.advait.contender.Contender;
import me.advait.contender.core.Msg;
import me.advait.contender.core.Sounds;
import me.advait.contender.dialog.DialogIcon;
import me.advait.contender.dialog.DialogPalette;
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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.*;

/**
 * One player is the Juggernaut: more hearts, less knockback, and glowing. Everyone else can only hit the
 * Juggernaut. Whoever takes them down becomes the next one. The longest total time as Juggernaut wins.
 */
public final class JuggernautGame extends ArenaGame {
    public static final class Type implements MinigameType {
        private final Contender plugin;
        public Type(Contender plugin) { this.plugin = plugin; }
        @Override public String id() { return "juggernaut"; }
        @Override public String name() { return "Juggernaut"; }
        @Override public String description() { return "One tough player against everyone. Take them down to take their place."; }
        @Override public DialogIcon icon() { return DialogIcon.ARMOR; }

        @Override public void openCreate(Player director) {
            new GameForm(plugin).open(director, new GameForm.Spec("Juggernaut", "Juggernaut",
                    "Take down the Juggernaut to take their place.", true, true, false,
                    List.of(GameForm.number("minutes", DialogIcon.CLOCK, "Length", 2, 15, 5, 1, "%s: %s min"),
                            GameForm.number("hearts", DialogIcon.HEART, "Juggernaut Hearts", 2, 5, 3, 1, "%s: %sx")), 3),
                    (p, result) -> {
                        Minigame game = create(result.name(), result.map(), result.kit(), result.roster(), result.values());
                        plugin.getMinigames().select(game);
                        new me.advait.contender.dialog.MinigameDialogs(plugin).control(p, game);
                    }, p -> new me.advait.contender.dialog.TournamentDialogs(plugin).formats(p));
        }

        @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
            if (map == null || !map.isComplete()) throw new IllegalArgumentException("Choose a map with both spawns set.");
            if (kit == null) throw new IllegalArgumentException("Choose a kit.");
            return new JuggernautGame(plugin, this, UUID.randomUUID(), name, roster, map, kit,
                    GameForm.intOption(options, "minutes", 2, 15, 5) * 60, GameForm.intOption(options, "hearts", 2, 5, 3));
        }

        @Override public Minigame restore() {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
            if (!yaml.contains("id")) return null;
            var extra = yaml.getConfigurationSection("extra");
            ArenaMap map = extra == null ? null : plugin.getMapManager().getMap(extra.getString("map", ""));
            Kit kit = extra == null ? null : plugin.getKitManager().getKit(extra.getString("kit", ""));
            JuggernautGame game = new JuggernautGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()), savedRoster(yaml),
                    map, kit, extra == null ? 300 : extra.getInt("seconds", 300), extra == null ? 3 : extra.getInt("hearts", 3));
            game.readSaved(yaml);
            return game;
        }
    }

    private static final NamespacedKey HEALTH = new NamespacedKey("contender", "juggernaut_health");
    private static final NamespacedKey STEADY = new NamespacedKey("contender", "juggernaut_knockback");
    private final int seconds, hearts;
    private final Map<UUID, Integer> takedowns = new HashMap<>();
    private final Random random = new Random();
    private UUID juggernaut;
    private TextDisplay crown;
    private int elapsed;

    JuggernautGame(Contender plugin, MinigameType type, UUID id, String name, Map<UUID, String> players, ArenaMap map, Kit kit, int seconds, int hearts) {
        super(plugin, type, id, name, players, map, kit);
        this.seconds = seconds;
        this.hearts = hearts;
    }

    @Override protected int minimumPlayers() { return 3; }

    @Override protected boolean canHit(Player attacker, Player victim) {
        return attacker.getUniqueId().equals(juggernaut) || victim.getUniqueId().equals(juggernaut);
    }

    @Override protected void setup(List<Player> players) {
        List<Location> ring = spawnRing(players.size());
        for (int i = 0; i < players.size(); i++) deploy(players.get(i), ring.get(i));
        broadcast(Msg.text("Only the Juggernaut can be hit, and only they can hit you. Take them down to become the Juggernaut.", DialogPalette.MUTED));
    }

    @Override protected void begin() {
        List<Player> candidates = fighters(null);
        if (!candidates.isEmpty()) crown(candidates.get(random.nextInt(candidates.size())), null);
        tasks.repeat(1, 1, this::follow);
    }

    private List<Player> fighters(UUID except) {
        List<Player> list = new ArrayList<>();
        for (Player player : playing()) if (player.getGameMode() != GameMode.SPECTATOR && !player.getUniqueId().equals(except)) list.add(player);
        return list;
    }

    // ---- The crown -----------------------------------------------------------------------------

    private void crown(Player player, Player from) {
        juggernaut = player.getUniqueId();
        Theme theme = plugin.getThemes().current();
        var health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.removeModifier(HEALTH);
            health.addTransientModifier(new AttributeModifier(HEALTH, hearts - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            player.setHealth(health.getValue());
        }
        var knockback = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.removeModifier(STEADY);
            knockback.addTransientModifier(new AttributeModifier(STEADY, 0.5, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        player.setGlowing(true);
        player.setFireTicks(0);
        Holograms.remove(crown);
        crown = Holograms.text(player.getLocation().add(0, player.getHeight() + 0.6, 0), Component.text("Juggernaut", theme.primary()), 0.01f,
                Display.Billboard.CENTER, theme.background(0), "juggernaut");
        crown.setTeleportDuration(2);
        player.hideEntity(plugin, crown);
        TextDisplay created = crown;
        tasks.later(2, () -> Holograms.animate(created, Holograms.scaled(1.4f), 6));
        Component name = plugin.getNameTagManager().displayName(player);
        broadcast(from == null ? name.append(Msg.text(" is the first Juggernaut.", DialogPalette.TEXT))
                : name.append(Msg.text(" took down ", DialogPalette.TEXT)).append(plugin.getNameTagManager().displayName(from))
                        .append(Msg.text(" and is the Juggernaut now.", DialogPalette.TEXT)));
        Msg.title(player, Component.text("You're the Juggernaut", theme.primary()), Msg.text("Survive as long as you can", DialogPalette.MUTED), 4, 40, 8);
        Sounds.ANNOUNCE.play(audience());
        plugin.getCelebrations().burst(player.getLocation().add(0, 1, 0));
        plugin.getStages().refreshDisplays();
    }

    private void uncrown(Player player) {
        if (player == null) return;
        var health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.removeModifier(HEALTH);
            if (player.getHealth() > health.getValue()) player.setHealth(health.getValue());
        }
        var knockback = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) knockback.removeModifier(STEADY);
        player.setGlowing(false);
    }

    private void follow() {
        Player player = juggernaut == null ? null : Bukkit.getPlayer(juggernaut);
        if (player == null || crown == null || !crown.isValid()) return;
        Location at = player.getLocation().add(0, player.getHeight() + 0.6, 0);
        if (!at.getWorld().equals(crown.getWorld())) return;
        crown.teleport(at);
    }

    // ---- Play ----------------------------------------------------------------------------------

    @Override protected void tick() {
        elapsed++;
        int left = Math.max(0, seconds - elapsed);
        String clock = String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
        Player holder = juggernaut == null ? null : Bukkit.getPlayer(juggernaut);
        Theme theme = plugin.getThemes().current();
        if (holder != null && fighting(holder)) {
            Participant participant = participant(juggernaut);
            participant.score++;
            participant.value = time((int) participant.score);
            Set<UUID> others = new HashSet<>(audience());
            others.remove(juggernaut);
            Msg.actionBar(others, Component.text(holder.getName(), theme.primary()).append(Msg.text(" is the Juggernaut  ·  " + clock, DialogPalette.MUTED)));
            Msg.status(holder, Component.text("You're the Juggernaut", theme.primary()).append(Msg.text("  ·  " + clock, DialogPalette.MUTED)));
            holder.getWorld().spawnParticle(Particle.DUST, holder.getLocation().add(0, 1, 0), 6, 0.4, 0.6, 0.4, 0,
                    new Particle.DustOptions(theme.primaryColor(), 1.2f));
        } else {
            Msg.actionBar(audience(), Msg.text("Waiting for a Juggernaut  ·  " + clock, DialogPalette.MUTED));
            if (holder == null || !isPlaying(juggernaut)) handOff(null, null);
        }
        if (left == 0) finish();
    }

    private static String time(int seconds) { return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }

    /** Passes the title to {@code next}, or to a random fighter when {@code next} is null. */
    private void handOff(Player next, Player from) {
        Player old = juggernaut == null ? null : Bukkit.getPlayer(juggernaut);
        uncrown(old);
        Holograms.remove(crown);
        crown = null;
        juggernaut = null;
        if (next == null) {
            List<Player> candidates = fighters(old == null ? null : old.getUniqueId());
            if (candidates.isEmpty()) return;
            next = candidates.get(random.nextInt(candidates.size()));
        }
        crown(next, from);
    }

    @Override protected void killed(Player victim, Player killer) {
        if (!isPlaying(victim.getUniqueId())) return;
        boolean wasJuggernaut = victim.getUniqueId().equals(juggernaut);
        plugin.getDeathEffect().play(victim);
        if (wasJuggernaut) {
            boolean earned = killer != null && !killer.equals(victim) && fighting(killer);
            if (earned) takedowns.merge(killer.getUniqueId(), 1, Integer::sum);
            else broadcast(plugin.getNameTagManager().displayName(victim).append(Msg.text(" fell. Passing the title on.", DialogPalette.MUTED)));
            uncrown(victim);
            bench(victim);
            handOff(earned ? killer : null, earned ? victim : null);
            if (earned) {
                var max = killer.getAttribute(Attribute.MAX_HEALTH);
                killer.setHealth(max == null ? 20 : max.getValue());
            }
        } else {
            bench(victim);
            Player holder = juggernaut == null ? null : Bukkit.getPlayer(juggernaut);
            // A small heal for each hunter the Juggernaut takes out.
            if (holder != null && holder.equals(killer)) holder.setHealth(Math.min(holder.getHealth() + 4, holder.getAttribute(Attribute.MAX_HEALTH).getValue()));
        }
        Msg.title(victim, Msg.text("Respawning", DialogPalette.ACCENT), Component.empty(), 0, 50, 10);
        tasks.later(60, () -> {
            if (state != State.RUNNING || !isPlaying(victim.getUniqueId()) || !victim.isOnline()) return;
            List<Location> ring = spawnRing(Math.max(4, roster().size()));
            deploy(victim, ring.get(random.nextInt(ring.size())));
        });
    }

    @Override protected void playerLeft(Participant participant) {
        participant.status = Status.OUT;
        if (participant.id.equals(juggernaut)) handOff(null, null);
        checkEnd();
    }

    @Override public void withdraw(UUID player) {
        boolean holder = player.equals(juggernaut);
        if (holder) uncrown(Bukkit.getPlayer(player));
        super.withdraw(player);
        if (holder && state == State.RUNNING) handOff(null, null);
    }

    @Override protected void checkEnd() {
        if (state == State.RUNNING && playing().size() < 2) finish();
    }

    @Override protected void cleanup() {
        uncrown(juggernaut == null ? null : Bukkit.getPlayer(juggernaut));
        Holograms.remove(crown);
        crown = null;
        super.cleanup();
    }

    @Override protected List<StandingsLayout.Row> standings() {
        List<Participant> order = new ArrayList<>(roster().stream().filter(p -> p.status != Status.WITHDRAWN).toList());
        order.sort(Comparator.comparingDouble((Participant p) -> p.score).reversed()
                .thenComparing(Comparator.comparingInt((Participant p) -> takedowns.getOrDefault(p.id, 0)).reversed()));
        List<StandingsLayout.Row> rows = new ArrayList<>();
        for (Participant p : order) {
            rows.add(new StandingsLayout.Row(p.id, p.name, time((int) p.score), p.id.equals(juggernaut) && started() && !finished(), p.status == Status.OUT));
        }
        return rows;
    }

    @Override protected void writeExtra(ConfigurationSection section) {
        super.writeExtra(section);
        section.set("seconds", seconds);
        section.set("hearts", hearts);
    }

    // ---- Director corrections ------------------------------------------------------------------

    @Override public ScoreEdit scoreEdit(Participant participant) {
        return participant.status == Status.WITHDRAWN ? null : new ScoreEdit("Seconds as Juggernaut", 0, seconds, (int) participant.score);
    }

    @Override protected void applyScore(Participant participant, int value) {
        participant.score = value;
        participant.value = time(value);
    }
}
