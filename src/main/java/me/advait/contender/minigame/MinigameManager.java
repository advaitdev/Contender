package me.advait.contender.minigame;

import me.advait.contender.Contender;
import me.advait.contender.util.YamlStorage;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;
import java.util.*;
import java.util.logging.Level;

/** Coordinates event selection and ownership without putting mode rules in the tournament scheduler. */
public final class MinigameManager {
    private final Contender plugin;
    private final File file;
    private final Map<String, MinigameMode> modes = new LinkedHashMap<>();
    private String selected;
    public MinigameManager(Contender plugin) {
        this.plugin = plugin; file = new File(plugin.getDataFolder(), "minigame-selection.yml");
        selected = YamlConfiguration.loadConfiguration(file).getString("selected");
    }
    public void register(MinigameMode mode) {
        if (modes.putIfAbsent(mode.id(), mode) != null) throw new IllegalStateException("Duplicate minigame mode " + mode.id());
    }
    public List<MinigameMode> modes() { return List.copyOf(modes.values()); }
    public MinigameMode selected() {
        // Migrate the original race selection lazily after its store has loaded.
        String id = selected == null && plugin.getRaceManager() != null && plugin.getRaceManager().displayed() != null ? "mace_race" : selected;
        MinigameMode mode = modes.get(id);
        return mode == null || mode.eventId() == null ? null : mode;
    }
    public void beforeCreate(String modeId) {
        for (MinigameMode mode : modes.values()) {
            if (mode.busy()) throw new IllegalStateException("Wait for " + mode.displayName() + " to finish cleaning up.");
            if (mode.eventId() != null && !mode.terminal()) throw new IllegalStateException("Finish or cancel " + mode.displayName() + " first.");
        }
        var tournament = plugin.getTournamentManager().current();
        if (tournament != null && !tournament.isComplete() && !tournament.isCancelled()) throw new IllegalStateException("Finish or cancel the round robin first.");
        if (!plugin.getTournamentManager().playing().isEmpty()) throw new IllegalStateException("Wait for the last matches to finish cleaning up.");
    }
    public void beforeStart(String modeId) {
        if (plugin.getVoteManager().isVoteActive()) throw new IllegalStateException("Wait for the vote to finish.");
        for (MinigameMode mode : modes.values()) if (!mode.id().equals(modeId) && mode.busy()) throw new IllegalStateException("Wait for " + mode.displayName() + " to finish.");
    }
    public void select(String modeId) {
        if (!modes.containsKey(modeId)) throw new IllegalArgumentException("Unknown event type.");
        saveSelection(modeId); refresh();
    }
    public void selectRoundRobin() {
        for (MinigameMode mode : modes.values()) {
            if (mode.busy() || mode.eventId() != null && !mode.terminal()) throw new IllegalStateException("Finish or cancel " + mode.displayName() + " first.");
        }
        if (plugin.getRaceManager() != null) plugin.getRaceManager().selectRoundRobin();
        saveSelection("");
    }
    private void saveSelection(String id) {
        var yaml = new YamlConfiguration(); yaml.set("selected", id); YamlStorage.save(yaml, file); selected = id;
    }
    public boolean active() { return modes.values().stream().anyMatch(MinigameMode::active); }
    public boolean busy() { return modes.values().stream().anyMatch(MinigameMode::busy); }
    public UUID activityId(UUID id) {
        return modes.values().stream().filter(mode -> mode.owns(id)).map(MinigameMode::eventId).filter(Objects::nonNull).findFirst().orElse(null);
    }
    public boolean sameEvent(UUID first, UUID second) {
        UUID event = activityId(first); return event != null && event.equals(activityId(second));
    }
    public boolean owns(UUID id) { return modes.values().stream().anyMatch(mode -> mode.owns(id)); }
    public boolean isReserved(UUID id) { return modes.values().stream().anyMatch(mode -> mode.isReserved(id)); }
    public boolean isPlaying(UUID id) { return modes.values().stream().anyMatch(mode -> mode.isPlaying(id)); }
    public boolean isSpectator(UUID id) { return modes.values().stream().anyMatch(mode -> mode.isSpectator(id)); }
    public boolean pendingReturn(UUID id) { return modes.values().stream().anyMatch(mode -> mode.pendingReturn(id)); }
    public boolean inArena(Location location) { return modes.values().stream().anyMatch(mode -> mode.inArena(location)); }
    public boolean leaveSpectating(Player player) {
        return modes.values().stream().anyMatch(mode -> mode.leaveSpectating(player));
    }
    public void withdraw(UUID id) { modes.values().forEach(mode -> mode.withdraw(id)); }
    /** Stop scheduling first; one broken mode must not prevent the others from being cleared. */
    public List<String> forceCancelAll() {
        List<String> failures = new ArrayList<>();
        cancelStep("Round robin", () -> plugin.getTournamentManager().forceCancel(), failures);
        cancelStep("Duels", () -> plugin.getDuelManager().forceCancelAll(), failures);
        for (MinigameMode mode : modes.values()) cancelStep(mode.displayName(), mode::forceCancel, failures);
        cancelStep("Vote", () -> plugin.getVoteManager().forceCancel(), failures);
        cancelStep("Event selection", () -> saveSelection(""), failures);
        cancelStep("Displays", this::refresh, failures);
        return List.copyOf(failures);
    }
    private void cancelStep(String name, Runnable action, List<String> failures) {
        try { action.run(); }
        catch (RuntimeException | LinkageError failure) {
            failures.add(name);
            plugin.getLogger().log(Level.SEVERE, "Force cancellation failed: " + name, failure);
        }
    }
    public boolean restore(Player player) {
        for (MinigameMode mode : modes.values()) if (mode.pendingReturn(player.getUniqueId()) && !mode.restore(player)) return false;
        return true;
    }
    public void refresh() {
        if (plugin.getTabManager() != null) plugin.getTabManager().refresh();
        if (plugin.getTournamentManager() != null) plugin.getTournamentManager().board().update(plugin.getTournamentManager().current(), plugin.getTournamentManager().playing());
        plugin.refreshVoiceRouting(); plugin.refreshHackerAttributes();
    }
}
