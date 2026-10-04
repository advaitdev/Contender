package me.advait.contender.minigame.manhunt;

import me.advait.contender.util.Teleports;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import me.advait.contender.Contender;
import me.advait.contender.dialog.*;
import me.advait.contender.kit.Kit;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.minigame.Minigame;
import me.advait.contender.minigame.MinigameType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;

import static me.advait.contender.dialog.DialogPalette.*;

/** Manhunt setup (preparing a fresh End), the create form, and reloading the last result. */
public final class ManhuntType implements MinigameType, Listener, AutoCloseable {
    private static final int WIDE = 300, NAV = 150;
    private final Contender plugin;
    private final ManhuntWorld world;
    private final Dialogs dialogs;
    private final BukkitTask freezer;

    public ManhuntType(Contender plugin) {
        this.plugin = plugin;
        this.world = new ManhuntWorld(plugin);
        this.dialogs = new Dialogs(plugin);
        try {
            world.reload(plugin::isEnabled).whenComplete((ignored, failure) -> {
                if (failure != null) plugin.getLogger().log(Level.WARNING, "Could not load the prepared Manhunt End", failure);
            });
        } catch (RuntimeException failure) { plugin.getLogger().log(Level.WARNING, "Could not load the prepared Manhunt End", failure); }
        // A prepared End stays frozen until a game starts, so nothing moves or despawns while it waits.
        freezer = plugin.getServer().getScheduler().runTaskTimer(plugin, world::freezeTick, 1, 1);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public ManhuntWorld world() { return world; }

    @Override public void close() {
        freezer.cancel();
        HandlerList.unregisterAll(this);
        world.close();
    }

    @EventHandler public void onSpawn(CreatureSpawnEvent event) { if (world.contains(event.getLocation())) world.freezeEntity(event.getEntity()); }

    @Override public String id() { return "manhunt"; }
    @Override public String name() { return "Manhunt"; }
    @Override public String description() { return "Runners race to kill the dragon while hunters try to stop them. One life each."; }
    @Override public DialogIcon icon() { return DialogIcon.COMPASS; }
    @Override public boolean hasSetup() { return true; }

    private String status() {
        return switch (world.state()) {
            case EMPTY -> "Not prepared";
            case PREPARING -> "Preparing…";
            case READY -> "Ready";
            case USED -> "Already used";
            case FAILED -> "Couldn't prepare";
        };
    }

    @Override public void openSetup(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogs.button(player, DialogIcon.REFRESH.label(world.ready() ? "Prepare Another End" : "Prepare a Fresh End", ACCENT),
                DialogText.muted("Creates a new End with a dragon. Takes a few seconds."), true, WIDE, (p, view) -> prepare(p, this::openSetup)));
        if (world.ready()) buttons.add(dialogs.button(player, DialogIcon.SPAWN.label("Visit the End", TEXT), DialogText.muted("Look around before the game. Everything stays frozen."), true, WIDE, (p, view) -> {
            if (!plugin.getRegistry().isFree(p.getUniqueId())) throw new IllegalStateException("Leave what you're doing first.");
            p.closeDialog();
            Teleports.to(p, world.spawn(0));
        }));
        dialogs.show(player, "Manhunt Setup", List.of(DialogBody.plainMessage(DialogText.lines(
                DialogText.detail("End", status()),
                DialogText.muted("Each game needs a fresh End.")), 320)),
                List.of(), buttons, 1, NAV, dialogs.button(player, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).minigameTools(p)));
    }

    private void prepare(Player player, java.util.function.Consumer<Player> then) {
        var current = plugin.getMinigames().current();
        if (current instanceof ManhuntGame game && game.started() && !game.finished()) throw new IllegalStateException("Finish the current Manhunt first.");
        if (world.world() != null && !world.world().getPlayers().isEmpty()) throw new IllegalStateException("Everyone has to leave the current End first.");
        player.closeDialog();
        Dialogs.tell(player, "Preparing a fresh End…");
        world.prepare(plugin::isEnabled).whenComplete((ignored, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (failure != null) plugin.getLogger().log(Level.WARNING, "Could not prepare the Manhunt End", failure);
            if (!player.isOnline()) return;
            if (failure != null) Dialogs.error(player, Dialogs.message(failure));
            else { Dialogs.tell(player, "The End is ready."); then.accept(player); }
        }));
    }

    @Override public void openCreate(Player director) {
        if (!world.ready()) {
            dialogs.show(director, "Prepare the End First", List.of(DialogBody.plainMessage(DialogText.muted("Manhunt needs a fresh End. It takes a few seconds."), 320)),
                    List.of(), List.of(dialogs.button(director, DialogIcon.REFRESH.label("Prepare a Fresh End", ACCENT), null, true, WIDE, (p, view) -> prepare(p, this::openCreate))),
                    1, NAV, dialogs.button(director, DialogIcon.BACK.label("Back", MUTED), null, true, NAV, (p, view) -> new TournamentDialogs(plugin).formats(p)));
            return;
        }
        List<DialogInput> extras = List.of(
                GameForm.number("runners", DialogIcon.RACE, "Runners", 1, 16, 1, 1, null),
                DialogInput.text("picked", DialogIcon.PLAYERS.label("Choose Runners (optional)")).initial("").maxLength(512).width(WIDE).build());
        new GameForm(plugin).open(director, new GameForm.Spec("Manhunt", "Manhunt",
                "Leave Choose Runners empty to pick them at random.",
                false, true, true, extras, 2, "None (start empty-handed)"),
                (p, result) -> {
                    Minigame game = create(result.name(), null, result.kit(), result.roster(), result.values());
                    plugin.getMinigames().select(game);
                    new MinigameDialogs(plugin).control(p, game);
                }, p -> new TournamentDialogs(plugin).formats(p));
    }

    @Override public Minigame create(String name, ArenaMap map, Kit kit, Map<UUID, String> roster, Map<String, String> options) {
        if (!world.ready()) throw new IllegalArgumentException("Prepare a fresh End first.");
        if (roster.size() < 2) throw new IllegalArgumentException("Manhunt needs at least two players.");
        Map<UUID, ManhuntGame.Team> teams = new LinkedHashMap<>();
        Set<String> picked = new HashSet<>();
        for (String entry : options.getOrDefault("picked", "").split("[,\\s]+")) if (!entry.isBlank()) picked.add(entry.toLowerCase(Locale.ROOT));
        if (!picked.isEmpty()) {
            for (var entry : roster.entrySet()) teams.put(entry.getKey(), picked.remove(entry.getValue().toLowerCase(Locale.ROOT)) ? ManhuntGame.Team.RUNNER : ManhuntGame.Team.HUNTER);
            if (!picked.isEmpty()) throw new IllegalArgumentException("Runners must also be in the player list: " + String.join(", ", picked));
        } else {
            int runners = Math.min(roster.size() - 1, GameForm.intOption(options, "runners", 1, 16, 1));
            List<UUID> order = new ArrayList<>(roster.keySet());
            Collections.shuffle(order);
            for (int i = 0; i < order.size(); i++) teams.put(order.get(i), i < runners ? ManhuntGame.Team.RUNNER : ManhuntGame.Team.HUNTER);
        }
        if (!teams.containsValue(ManhuntGame.Team.RUNNER) || !teams.containsValue(ManhuntGame.Team.HUNTER)) throw new IllegalArgumentException("Each team needs at least one player.");
        return new ManhuntGame(plugin, this, UUID.randomUUID(), name, roster, kit, teams, world.name());
    }

    @Override public Minigame restore() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Minigame.file(plugin, id()));
        if (!yaml.contains("id")) return null;
        var extra = yaml.getConfigurationSection("extra");
        if (extra == null) return null;
        Map<UUID, ManhuntGame.Team> teams = new LinkedHashMap<>();
        var saved = extra.getConfigurationSection("teams");
        if (saved != null) for (String key : saved.getKeys(false)) {
            try { teams.put(UUID.fromString(key), ManhuntGame.Team.valueOf(saved.getString(key))); }
            catch (IllegalArgumentException | NullPointerException ignored) { }
        }
        Kit kit = plugin.getKitManager().getKit(extra.getString("kit", ""));
        ManhuntGame game = new ManhuntGame(plugin, this, UUID.fromString(yaml.getString("id")), yaml.getString("name", name()), Minigame.savedRoster(yaml),
                kit, teams, extra.getString("world", ""));
        game.readSaved(yaml);
        game.restoreWinner(extra.getString("winner"));
        return game;
    }
}
