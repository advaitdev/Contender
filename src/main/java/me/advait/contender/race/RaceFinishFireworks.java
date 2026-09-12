package me.advait.contender.race;

import me.advait.contender.game.AbstractGameState;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns the celebration rockets independently of the race's arena cleanup. */
final class RaceFinishFireworks extends AbstractGameState {
    private static final NamespacedKey MARKER = new NamespacedKey("contender", "race_finish_firework");
    private static final List<FireworkEffect> EFFECTS = List.of(
            FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.RED, Color.ORANGE, Color.YELLOW).withFade(Color.YELLOW).trail(true).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.AQUA, Color.BLUE, Color.FUCHSIA).withFade(Color.PURPLE).flicker(true).trail(true).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.LIME, Color.YELLOW, Color.AQUA).withFade(Color.WHITE).flicker(true).trail(true).build());
    private final Set<Firework> rockets = new HashSet<>();

    RaceFinishFireworks(Plugin plugin) { super(plugin); }

    void launch(Location finish) {
        if (!isEnabled() || finish.getWorld() == null) return;
        rockets.removeIf(rocket -> !rocket.isValid());
        for (int i = 0; i < EFFECTS.size(); i++) {
            int index = i;
            Location launch = finish.clone().add((i - 1) * .7, 1.5, 0);
            Firework rocket = finish.getWorld().spawn(launch, Firework.class, firework -> {
                firework.setPersistent(false);
                firework.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
                var meta = firework.getFireworkMeta();
                meta.clearEffects(); meta.addEffect(EFFECTS.get(index)); meta.setPower(0);
                firework.setFireworkMeta(meta);
                firework.setTicksFlown(0);
                // All three burst before the final finisher's 20-tick arena cleanup.
                firework.setTicksToDetonate(8 + index * 3);
                firework.setVelocity(new Vector((index - 1) * .04, .12, 0));
            });
            rockets.add(rocket);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void exploded(FireworkExplodeEvent event) { rockets.remove(event.getEntity()); }
    void clear() { rockets.forEach(Firework::remove); rockets.clear(); }
    @Override protected void onDisable() { clear(); }
}
