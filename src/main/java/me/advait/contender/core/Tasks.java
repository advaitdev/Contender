package me.advait.contender.core;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Tasks started by one owner, cancelled together. Every callback is guarded so a failure is logged
 * with its owner's name instead of disappearing into a generic scheduler warning. Main thread only.
 */
public final class Tasks {
    private final Plugin plugin;
    private final String owner;
    private final Set<BukkitTask> running = new HashSet<>();
    private boolean closed;

    public Tasks(Plugin plugin, String owner) {
        this.plugin = plugin;
        this.owner = owner;
    }

    public BukkitTask later(long delayTicks, Runnable action) {
        if (closed || !plugin.isEnabled()) return null;
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            running.remove(holder[0]);
            run(action);
        }, Math.max(0, delayTicks));
        running.add(holder[0]);
        return holder[0];
    }

    public BukkitTask repeat(long delayTicks, long periodTicks, Runnable action) {
        if (closed || !plugin.isEnabled()) return null;
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> run(action),
                Math.max(0, delayTicks), Math.max(1, periodTicks));
        running.add(task);
        return task;
    }

    public void cancel(BukkitTask task) {
        if (task == null) return;
        task.cancel();
        running.remove(task);
    }

    /** Cancels everything but keeps accepting new work, for owners that restart (for example a new round). */
    public void cancelAll() {
        for (BukkitTask task : Set.copyOf(running)) task.cancel();
        running.clear();
    }

    /** Cancels everything and refuses new work. */
    public void close() {
        closed = true;
        cancelAll();
    }

    public boolean isClosed() { return closed; }

    private void run(Action action) {
        try { action.run(); }
        catch (Throwable failure) {
            plugin.getLogger().log(Level.SEVERE, owner + " task failed", failure);
        }
    }

    private void run(Runnable action) { run((Action) action::run); }

    @FunctionalInterface private interface Action { void run() throws Exception; }
}
