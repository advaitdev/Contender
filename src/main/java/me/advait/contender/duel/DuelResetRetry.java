package me.advait.contender.duel;

import java.util.function.BiConsumer;
import java.util.logging.Level;

/** One retry at a time, owned by the current state and invalidated after success or cancellation. */
final class DuelResetRetry {
    private final Duel duel;
    private final BiConsumer<Runnable, Long> schedule;
    private final String operation;
    private boolean pending;
    private int attempts;
    private long generation;

    DuelResetRetry(Duel duel, BiConsumer<Runnable, Long> schedule) {
        this(duel, schedule, "reset");
    }

    DuelResetRetry(Duel duel, BiConsumer<Runnable, Long> schedule, String operation) {
        this.duel = duel;
        this.schedule = schedule;
        this.operation = operation;
    }

    void retry(Throwable failure, Runnable action) {
        if (pending) return;
        pending = true;
        long token = ++generation;
        long delay = Math.min(200L, 40L << Math.min(attempts++, 3));
        if (attempts == 1 || attempts % 6 == 0) {
            duel.getPlugin().getLogger().log(Level.WARNING,
                    "Arena " + duel.getMap().getId() + " " + operation + " delayed; keeping the match and score. Retrying in " + delay / 20 + "s.", failure);
        }
        schedule.accept(() -> {
            if (token != generation) return;
            pending = false;
            action.run();
        }, delay);
    }

    void clear() { pending = false; attempts = 0; generation++; }
}
