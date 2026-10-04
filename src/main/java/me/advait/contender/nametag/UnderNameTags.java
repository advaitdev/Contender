package me.advait.contender.nametag;

import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Module;
import me.advait.contender.kit.Kit;
import me.advait.contender.util.Tags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * A line under a player's name: what their game says about them (Manhunt's Runner or Hunter), and "17 ❤" when
 * their kit turns on Health Under Name, for example "Runner · 17 ❤".
 *
 * Each line is a text display that follows its player, between their head and their name. The scoreboard's
 * below-name line would be simpler, but the game only draws it within 10 blocks. Players don't see their own.
 */
public final class UnderNameTags extends Module {
    private static final Component HEART = Component.text("❤", NamedTextColor.RED);
    private static final Component DIVIDER = Component.text(" · ", NamedTextColor.GRAY);
    /** How far above the top of the head the line sits, so it ends just under the name tag. */
    private static final double ABOVE_HEAD = 0.09;
    /** A little smaller than a name tag, so it fits between the head and the name. */
    private static final float SCALE = 0.85f;
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    /** What each line says now, so the text only changes when it has to. */
    private final Map<UUID, Component> shown = new HashMap<>();
    private int ticks;

    public UnderNameTags(Contender plugin) { super(plugin); }

    @Override protected void onEnable() { tasks.repeat(1L, 1L, this::update); }

    @Override protected void onDisable() {
        displays.values().forEach(TextDisplay::remove);
        displays.clear();
        shown.clear();
    }

    /** Health on a 0–20 scale: whole numbers, with one decimal place below 3. */
    static String format(double health, double max) {
        double value = Math.clamp(max <= 0 ? 0 : health / max * 20, 0, 20);
        return value < 3 ? String.format(Locale.ROOT, "%.1f", value) : Long.toString(Math.round(value));
    }

    private void update() {
        Set<UUID> active = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Component text = line(player);
            if (text == null) continue;
            UUID id = player.getUniqueId();
            active.add(id);
            Location at = player.getLocation().add(0, player.getHeight() + ABOVE_HEAD, 0);
            TextDisplay display = displays.get(id);
            if (display == null || !display.isValid() || !display.getWorld().equals(player.getWorld())) {
                if (display != null) display.remove();
                display = spawn(player, at);
                displays.put(id, display);
                shown.remove(id);
            } else {
                display.teleport(at);
            }
            if (!text.equals(shown.get(id))) {
                display.text(text);
                shown.put(id, text);
            }
            // Like a name tag: seen through walls unless the player is sneaking.
            boolean sneaking = player.isSneaking();
            if (display.isSeeThrough() == sneaking) display.setSeeThrough(!sneaking);
        }
        for (Iterator<Map.Entry<UUID, TextDisplay>> it = displays.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (active.contains(entry.getKey())) continue;
            entry.getValue().remove();
            shown.remove(entry.getKey());
            it.remove();
        }
        // Anyone who can't see the player (hidden, vanished) doesn't see their health either.
        if (++ticks % 10 == 0) for (var entry : displays.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null) continue;
            for (Player viewer : owner.getWorld().getPlayers()) {
                if (viewer.equals(owner)) continue;
                boolean see = viewer.canSee(owner);
                if (see != viewer.canSee(entry.getValue())) {
                    if (see) viewer.showEntity(plugin, entry.getValue());
                    else viewer.hideEntity(plugin, entry.getValue());
                }
            }
        }
    }

    private TextDisplay spawn(Player owner, Location at) {
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            Tags.managed(created, "health");
            created.setBillboard(Display.Billboard.CENTER);
            created.setAlignment(TextDisplay.TextAlignment.CENTER);
            created.setShadowed(false);
            created.setDefaultBackground(true);
            created.setSeeThrough(true);
            created.setBrightness(new Display.Brightness(15, 15));
            created.setTransformation(me.advait.contender.display.Holograms.scaled(SCALE));
            // Players move smoothly over 3 ticks on other screens; match it so the line keeps up.
            created.setTeleportDuration(3);
        });
        owner.hideEntity(plugin, display);
        return display;
    }

    /** The line for a player who's playing (not watching) and visible with a name tag, or null for none. */
    private Component line(Player player) {
        ActivityRegistry.Claim claim = plugin.getRegistry().claim(player.getUniqueId());
        if (claim == null || claim.involvement() != ActivityRegistry.Involvement.PLAYING || claim.activity() == null) return null;
        if (player.getGameMode() == GameMode.SPECTATOR || player.isDead() || player.isInvisible()
                || player.hasPotionEffect(PotionEffectType.INVISIBILITY) || plugin.getNameTagManager().hidden(player.getUniqueId())) return null;
        Activity activity = claim.activity();
        Component label = activity.underName(player);
        Kit kit = activity.kit();
        if (kit == null || !kit.isHealthUnderName()) return label;
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        Component health = Component.text(format(player.getHealth(), max == null ? 20 : max.getValue()) + " ", NamedTextColor.WHITE).append(HEART);
        return label == null ? health : label.append(DIVIDER).append(health);
    }
}
