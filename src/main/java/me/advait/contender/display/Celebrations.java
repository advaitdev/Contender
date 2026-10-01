package me.advait.contender.display;

import me.advait.contender.Contender;
import me.advait.contender.util.Tags;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Harmless fireworks and particle bursts in the selected color scheme. */
public final class Celebrations implements Listener {
    private static final String TAG = "celebration";
    private final Contender plugin;

    public Celebrations(Contender plugin) { this.plugin = plugin; }

    public void fireworks(Location at, int count) {
        Theme theme = plugin.getThemes().current();
        if (at.getWorld() == null) return;
        for (int i = 0; i < count; i++) {
            int index = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> launch(at, theme, index), i * 4L);
        }
    }

    private void launch(Location origin, Theme theme, int index) {
        if (origin.getWorld() == null) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = origin.clone().add(random.nextDouble(-1.2, 1.2), 1.2, random.nextDouble(-1.2, 1.2));
        List<Color> colors = theme.fireworks();
        FireworkEffect effect = FireworkEffect.builder()
                .with(index % 3 == 0 ? FireworkEffect.Type.BALL_LARGE : index % 3 == 1 ? FireworkEffect.Type.STAR : FireworkEffect.Type.BURST)
                .withColor(colors).withFade(colors.getFirst()).trail(true).flicker(index % 2 == 0).build();
        origin.getWorld().spawn(at, Firework.class, firework -> {
            Tags.managed(firework, TAG);
            var meta = firework.getFireworkMeta();
            meta.clearEffects();
            meta.addEffect(effect);
            meta.setPower(0);
            firework.setFireworkMeta(meta);
            firework.setTicksToDetonate(10 + random.nextInt(8));
            firework.setVelocity(new Vector(random.nextDouble(-0.05, 0.05), 0.25, random.nextDouble(-0.05, 0.05)));
        });
    }

    /** A ring of colored dust, used for eliminations and reveals. */
    public void ring(Location center, double radius, int points) {
        Theme theme = plugin.getThemes().current();
        if (center.getWorld() == null) return;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Color color = theme.fireworks().get(i % theme.fireworks().size());
            center.getWorld().spawnParticle(Particle.DUST, at, 2, 0.02, 0.02, 0.02, 0, new Particle.DustOptions(color, 1.4f));
        }
    }

    public void burst(Location at) {
        Theme theme = plugin.getThemes().current();
        if (at.getWorld() == null) return;
        for (Color color : theme.fireworks()) {
            at.getWorld().spawnParticle(Particle.DUST, at, 12, 0.5, 0.8, 0.5, 0, new Particle.DustOptions(color, 1.6f));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework && TAG.equals(Tags.owner(firework))) event.setCancelled(true);
    }
}
