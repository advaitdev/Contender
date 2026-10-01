package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.*;

/** A big number floating above each candidate's head, so viewers can follow the vote at a glance. */
final class VoteBadges {
    private static final double HEIGHT = 2.55;
    private final Contender plugin;
    private final Map<UUID, TextDisplay> badges = new HashMap<>();
    private final Map<UUID, Component> texts = new HashMap<>();

    VoteBadges(Contender plugin) { this.plugin = plugin; }

    void show(VoteSession.Candidate candidate) {
        Theme theme = plugin.getThemes().current();
        setText(candidate.id(), Component.text(Integer.toString(candidate.number()), theme.primary()));
    }

    void showCount(VoteSession.Candidate candidate, int votes) {
        Theme theme = plugin.getThemes().current();
        setText(candidate.id(), Component.text(Integer.toString(candidate.number()), theme.primary())
                .appendNewline().append(Component.text(votes + (votes == 1 ? " vote" : " votes"), theme.secondary())));
    }

    void setText(UUID player, Component text) {
        texts.put(player, text);
        TextDisplay badge = badges.get(player);
        if (badge != null && badge.isValid()) badge.text(text);
    }

    /** Called every tick: badges follow their players, and move worlds with them. */
    void follow() {
        for (var entry : List.copyOf(texts.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            TextDisplay badge = badges.get(entry.getKey());
            if (player == null) { remove(entry.getKey(), false); continue; }
            Location at = player.getLocation().add(0, HEIGHT, 0);
            if (badge == null || !badge.isValid() || !badge.getWorld().equals(at.getWorld())) {
                if (badge != null) badge.remove();
                badge = spawn(player, at, entry.getValue());
                badges.put(entry.getKey(), badge);
            } else if (badge.getLocation().distanceSquared(at) > 0.0004) {
                badge.teleport(at);
            }
        }
    }

    private TextDisplay spawn(Player owner, Location at, Component text) {
        TextDisplay badge = Holograms.text(at, text, 0.01f, Display.Billboard.CENTER,
                plugin.getThemes().current().background(55), "vote_badge");
        badge.setTeleportDuration(2);
        owner.hideEntity(plugin, badge);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(badge, Holograms.scaled(1.6f), 6), 2L);
        return badge;
    }

    TextDisplay badge(UUID player) { return badges.get(player); }

    /** A quick bounce, used when a count changes during the reveal. */
    void pop(UUID player, float scale) {
        TextDisplay badge = badges.get(player);
        if (badge == null || !badge.isValid()) return;
        Holograms.animate(badge, Holograms.scaled(scale * 1.25f), 2);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(badge, Holograms.scaled(scale), 4), 3L);
    }

    void scale(UUID player, float scale, int ticks) {
        TextDisplay badge = badges.get(player);
        if (badge != null && badge.isValid()) Holograms.animate(badge, Holograms.scaled(scale), ticks);
    }

    void remove(UUID player, boolean animate) {
        texts.remove(player);
        TextDisplay badge = badges.remove(player);
        if (badge == null || !badge.isValid()) return;
        if (!animate) { badge.remove(); return; }
        Holograms.animate(badge, Holograms.scaled(0.01f), 6);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.remove(badge), 8L);
    }

    void clear() {
        for (UUID id : List.copyOf(texts.keySet())) remove(id, false);
        badges.values().forEach(Holograms::remove);
        badges.clear();
    }

    Set<UUID> shown() { return Collections.unmodifiableSet(texts.keySet()); }
}
