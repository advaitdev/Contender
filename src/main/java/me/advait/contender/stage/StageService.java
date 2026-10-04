package me.advait.contender.stage;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import me.advait.contender.util.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Tracks the selected stage and tells other features when it starts and ends. */
public final class StageService extends Module {
    public interface Listener {
        default void stageStarted(Stage stage) { }
        default void stageEnded(Stage stage) { }
    }

    private final List<Listener> listeners = new ArrayList<>();
    private final File file;
    private Stage current;
    private boolean startNotified;

    public StageService(Contender plugin) {
        super(plugin);
        file = new File(plugin.getDataFolder(), "stage.yml");
    }

    public void listen(Listener listener) { listeners.add(listener); }

    public Stage current() { return current; }

    /** The id of the saved selection ("round_robin" or a minigame id), restored at startup. */
    public String savedKind() { return YamlConfiguration.loadConfiguration(file).getString("selected", ""); }

    /** A stage is in progress and has not finished or been cancelled. */
    public boolean busy() { return current != null && !current.finished(); }

    public void requireFree() {
        if (busy()) throw new IllegalStateException("Finish or cancel " + current.name() + " first.");
    }

    /** Makes a newly created stage the current one. */
    public void select(Stage stage, String kind) {
        if (current != null && current != stage && !current.finished()) {
            throw new IllegalStateException("Finish or cancel " + current.name() + " first.");
        }
        if (current != null && current != stage && startNotified) ended(current);
        current = stage;
        startNotified = stage != null && stage.started() && !stage.finished();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("selected", kind == null ? "" : kind);
        YamlStorage.save(yaml, file);
        refreshDisplays();
    }

    /** Restores the selection at startup without firing start events. */
    public void restore(Stage stage) {
        current = stage;
        startNotified = false;
        refreshDisplays();
    }

    /** Called by a stage the first time play begins. */
    public void started(Stage stage) {
        if (stage != current || startNotified) return;
        startNotified = true;
        for (Listener listener : List.copyOf(listeners)) {
            try { listener.stageStarted(stage); }
            catch (RuntimeException failure) { plugin.getLogger().log(Level.SEVERE, "A stage start handler failed", failure); }
        }
        refreshDisplays();
    }

    /** Called by a stage when it finishes or is cancelled. */
    public void ended(Stage stage) {
        if (stage != current) return;
        boolean notify = startNotified;
        startNotified = false;
        if (notify) for (Listener listener : List.copyOf(listeners)) {
            try { listener.stageEnded(stage); }
            catch (RuntimeException failure) { plugin.getLogger().log(Level.SEVERE, "A stage end handler failed", failure); }
        }
        refreshDisplays();
    }

    /** Whether the current stage has started and not yet ended. */
    public boolean running() { return current != null && startNotified && !current.finished(); }

    public void refreshDisplays() {
        if (plugin.getTabManager() != null && plugin.getTabManager().isEnabled()) plugin.getTabManager().refresh();
        if (plugin.getBoard() != null && plugin.getBoard().isEnabled()) plugin.getBoard().refresh();
    }
}
