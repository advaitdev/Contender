package me.advait.contender.testutil;

import me.advait.contender.Contender;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs queued callbacks explicitly, including cancelled ones, to exercise lifecycle races. */
public final class StateTestServer implements AutoCloseable {
    public final Contender plugin = mock(Contender.class);
    public final Server server = mock(Server.class);
    public final PluginManager plugins = mock(PluginManager.class);
    public final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    public final HandlerList handlers = new HandlerList();
    public final List<Scheduled> scheduled = new ArrayList<>();

    public record Scheduled(Runnable callback, BukkitTask task, long delay, boolean repeating) {
        public void run() { callback.run(); }
    }

    public StateTestServer() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> {
            Listener listener = call.getArgument(0);
            handlers.register(new RegisteredListener(listener, (ignored, event) -> {}, EventPriority.NORMAL, plugin, false));
            return null;
        }).when(plugins).registerEvents(any(Listener.class), eq(plugin));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call ->
                schedule(call.getArgument(1), call.getArgument(2), false));
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call ->
                schedule(call.getArgument(1), call.getArgument(2), true));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call ->
                schedule(call.getArgument(1), 1L, false));
    }

    private BukkitTask schedule(Runnable callback, long delay, boolean repeating) {
        BukkitTask task = mock(BukkitTask.class);
        scheduled.add(new Scheduled(callback, task, delay, repeating));
        return task;
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(plugin);
    }
}
