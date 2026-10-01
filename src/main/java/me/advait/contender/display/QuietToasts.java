package me.advait.contender.display;

import me.advait.contender.Contender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;

/**
 * Stops "Advancement Made" and "New Recipes Unlocked" toasts, which otherwise pop up on camera whenever a
 * kit hands out armor or items. Turn off with display.hide-advancement-toasts: false.
 */
public final class QuietToasts implements Listener {
    private final Contender plugin;

    public QuietToasts(Contender plugin) { this.plugin = plugin; }

    private boolean enabled() { return plugin.getConfig().getBoolean("display.hide-advancement-toasts", true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCriterion(PlayerAdvancementCriterionGrantEvent event) {
        if (enabled() && !event.getAdvancement().getKey().getKey().startsWith("recipes/")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRecipe(PlayerRecipeDiscoverEvent event) {
        if (enabled()) event.setCancelled(true);
    }
}
