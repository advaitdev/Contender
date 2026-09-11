package me.advait.contender.game;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A phase's listeners and synchronous tasks share one lifecycle. Call from the server thread. */
public abstract class AbstractGameState implements Listener {

    protected final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean enabled;
    private long generation;

    protected AbstractGameState(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public final void enable() {
        if (enabled) return;
        enabled = true;
        generation++;
        try {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            onEnable();
        } catch (RuntimeException | Error failure) {
            try {
                disable();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public final void disable() {
        if (!enabled) return;
        enabled = false;
        generation++;
        try {
            for (BukkitTask task : tasks) task.cancel();
        } finally {
            tasks.clear();
            HandlerList.unregisterAll(this);
            onDisable();
        }
    }

    public final boolean isEnabled() {
        return enabled;
    }

    protected void onEnable() { }

    protected void onDisable() { }

    /** Also invalidates callbacks already queued when a state is disabled and re-enabled. */
    protected final Runnable guard(Runnable action) {
        long expectedGeneration = generation;
        return () -> {
            if (enabled && generation == expectedGeneration) action.run();
        };
    }

    protected final BukkitTask runLater(Runnable action, long delayTicks) {
        requireEnabled();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, guard(action), delayTicks);
        tasks.add(task);
        return task;
    }

    protected final BukkitTask runRepeating(Runnable action, long delayTicks, long periodTicks) {
        requireEnabled();
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, guard(action), delayTicks, periodTicks);
        tasks.add(task);
        return task;
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Cannot schedule work for a disabled state");
    }
}
