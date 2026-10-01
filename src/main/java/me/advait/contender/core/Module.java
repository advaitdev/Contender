package me.advait.contender.core;

import me.advait.contender.Contender;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/** A long-lived feature: its listeners and tasks live exactly as long as it is enabled. */
public abstract class Module implements Listener {
    protected final Contender plugin;
    protected Tasks tasks;
    private boolean enabled;

    protected Module(Contender plugin) {
        this.plugin = plugin;
    }

    public final void enable() {
        if (enabled) return;
        enabled = true;
        tasks = new Tasks(plugin, getClass().getSimpleName());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        onEnable();
    }

    public final void disable() {
        if (!enabled) return;
        enabled = false;
        try { onDisable(); }
        finally {
            if (tasks != null) tasks.close();
            HandlerList.unregisterAll(this);
        }
    }

    public final boolean isEnabled() { return enabled; }

    protected void onEnable() { }

    protected void onDisable() { }
}
