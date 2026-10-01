package me.advait.contender.display;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import me.advait.contender.Contender;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Stops "Advancement Made" and "New Recipes Unlocked" toasts, which otherwise pop up on camera whenever a
 * kit hands out armor or items. Turn off with display.hide-advancement-toasts: false.
 *
 * <p>Progress is held back rather than thrown away: the advancement stays unfinished, so turning the
 * option off lets it complete normally later. Advancements that never show a toast, such as a
 * datapack's hidden triggers, are left alone.
 */
public final class QuietToasts implements Listener {
    private final Contender plugin;

    public QuietToasts(Contender plugin) { this.plugin = plugin; }

    private boolean enabled() { return plugin.getConfig().getBoolean("display.hide-advancement-toasts", true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCriterion(PlayerAdvancementCriterionGrantEvent event) {
        if (enabled() && showsToast(event.getAdvancement())) event.setCancelled(true);
    }

    private static boolean showsToast(Advancement advancement) {
        NamespacedKey key = advancement.getKey();
        // Vanilla recipe advancements are hidden, but completing one unlocks a recipe, which shows a toast.
        if (key.getNamespace().equals(NamespacedKey.MINECRAFT) && key.getKey().startsWith("recipes/")) return true;
        var display = advancement.getDisplay();
        return display != null && display.doesShowToast();
    }
}
