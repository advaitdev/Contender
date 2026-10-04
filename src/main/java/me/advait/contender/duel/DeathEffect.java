package me.advait.contender.duel;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import me.advait.contender.Contender;
import me.advait.contender.util.Tags;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;

/** A mannequin wearing the eliminated player's skin and armor plays the vanilla death animation. */
public final class DeathEffect implements Listener {
    private static final String TAG = "death_effect";
    private final Contender plugin;

    public DeathEffect(Contender plugin) { this.plugin = plugin; }

    public void play(Player player) {
        Location at = player.getLocation();
        if (at.getWorld() == null) return;
        try {
            Mannequin body = at.getWorld().spawn(at, Mannequin.class, mannequin -> {
                Tags.managed(mannequin, TAG);
                mannequin.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
                mannequin.setDescription(null);
                mannequin.setRotation(at.getYaw(), 0);
                mannequin.setImmovable(true);
                mannequin.setGravity(false);
                mannequin.setSilent(true);
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND}) {
                    var item = player.getInventory().getItem(slot);
                    if (item != null && !item.isEmpty()) mannequin.getEquipment().setItem(slot, item.clone());
                }
            });
            body.damage(1000);
            // Vanilla removes the body after its animation; this is a fallback if it survived.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (body.isValid()) body.remove(); }, 60L);
        } catch (RuntimeException failure) {
            plugin.getLogger().fine("Could not play a death effect: " + failure.getMessage());
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!TAG.equals(Tags.owner(event.getEntity()))) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShouldPlayDeathSound(true);
    }
}
