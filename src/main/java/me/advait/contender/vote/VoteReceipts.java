package me.advait.contender.vote;

import me.advait.contender.Contender;
import me.advait.contender.display.Holograms;
import me.advait.contender.display.Theme;
import me.advait.contender.util.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When votes aren't anonymous: once voting closes, a small display above each voter shows who they voted for,
 * so everyone can be held to their vote. It follows them around and goes away after {@link #SHOW_MILLIS}.
 */
final class VoteReceipts {
    static final long SHOW_MILLIS = 10_000;
    private final Contender plugin;
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private BukkitTask follow;
    private long until;

    VoteReceipts(Contender plugin) { this.plugin = plugin; }

    /** Whether receipts are up (the vote isn't over until they're gone). */
    boolean showing() { return System.currentTimeMillis() < until; }

    /** When the receipts started now will be gone. */
    long until() { return until; }

    void show(VoteSession session) {
        clear();
        Theme theme = plugin.getThemes().current();
        for (VoteSession.Candidate voter : session.candidates()) {
            Player player = Bukkit.getPlayer(voter.id());
            if (player == null || player.isDead()) continue;
            UUID choice = session.voteOf(voter.id());
            VoteSession.Candidate target = choice == null ? null : session.candidate(choice);
            Component text = target == null ? Component.text("Didn't vote", theme.muted())
                    : Component.text("Voted for", theme.muted()).appendNewline()
                            .append(StringUtil.getPlayerHead(target.id())).append(Component.text(" " + target.name(), theme.primary()));
            TextDisplay display = Holograms.text(above(player), text, 0.01f, Display.Billboard.CENTER, theme.background(plugin.getThemes().opacity()), "vote_receipt");
            displays.put(voter.id(), display);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> Holograms.animate(display, Holograms.scaled(1f), 6), 2L);
        }
        until = System.currentTimeMillis() + SHOW_MILLIS;
        long mine = until;
        follow = plugin.getServer().getScheduler().runTaskTimer(plugin, this::follow, 1L, 1L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (until == mine) clear(); }, SHOW_MILLIS / 50);
    }

    /** Keeps each display over its voter; a voter who died, left or became a spectator loses theirs. */
    private void follow() {
        if (!showing()) { clear(); return; }
        displays.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            TextDisplay display = entry.getValue();
            if (player == null || player.isDead() || player.getGameMode() == GameMode.SPECTATOR || !display.isValid()) {
                Holograms.remove(display);
                return true;
            }
            if (!display.getWorld().equals(player.getWorld())) {
                Holograms.remove(display);
                return true;
            }
            display.teleport(above(player));
            return false;
        });
    }

    /** Above the name tag. */
    private static Location above(Player player) { return player.getLocation().add(0, player.getHeight() + 0.75, 0); }

    void clear() {
        if (follow != null) { follow.cancel(); follow = null; }
        displays.values().forEach(Holograms::remove);
        displays.clear();
        until = 0;
    }
}
