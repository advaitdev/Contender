package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.stage.Stage;

import java.util.*;
import java.util.logging.Level;

/** Registers minigame types and holds the minigame that is currently selected as the stage. */
public final class MinigameService extends Module {
    private final Map<String, MinigameType> types = new LinkedHashMap<>();
    private Minigame current;

    public MinigameService(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        registerBuiltIns();
        String saved = plugin.getStages().savedKind();
        MinigameType type = types.get(saved);
        if (type != null && plugin.getStages().current() == null) {
            try {
                Minigame restored = type.restore();
                if (restored != null) {
                    current = restored;
                    plugin.getStages().restore(restored);
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Could not reload the last " + type.name() + " results", failure);
            }
        }
    }

    @Override protected void onDisable() {
        if (current != null && !current.finished() && current.state() != Minigame.State.READY) current.cancel("The server is stopping.");
        types.values().forEach(type -> { if (type instanceof AutoCloseable closeable) { try { closeable.close(); } catch (Exception ignored) { } } });
    }

    private void registerBuiltIns() {
        // Order here is the order shown in the event list.
        register(new me.advait.contender.minigame.race.RaceType(plugin));
        register(new me.advait.contender.minigame.games.LastStandGame.Type(plugin));
        register(new me.advait.contender.minigame.games.HillGame.Type(plugin));
        register(new me.advait.contender.minigame.games.GauntletGame.Type(plugin));
    }

    public void register(MinigameType type) {
        if (types.putIfAbsent(type.id(), type) != null) throw new IllegalStateException("Duplicate minigame " + type.id());
    }

    public Collection<MinigameType> types() { return Collections.unmodifiableCollection(types.values()); }
    public MinigameType type(String id) { return types.get(id); }
    public Minigame current() { return current; }

    public me.advait.contender.minigame.race.RaceEditor raceEditor() {
        return types.get("mace_race") instanceof me.advait.contender.minigame.race.RaceType race ? race.editor() : null;
    }

    /** Makes a newly created game the selected stage. */
    public void select(Minigame game) {
        Stage stage = plugin.getStages().current();
        if (stage != null && stage != game && !stage.finished()) throw new IllegalStateException("Finish or cancel " + stage.name() + " first.");
        current = game;
        plugin.getStages().select(game, game.type().id());
        game.save();
    }
}
