package me.advait.contender.nametag;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.advait.contender.Contender;
import me.advait.contender.activity.Activity;
import me.advait.contender.activity.ActivityRegistry;
import me.advait.contender.core.Module;
import me.advait.contender.kit.Kit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * "17 ❤" under the names of players whose kit turns on Health Under Name.
 *
 * The line under a name comes from the viewer's scoreboard and shows for every player they see, so only people in
 * (or watching) such a game get the health scoreboard. Everyone else on it gets a blank line.
 */
public final class HealthTags extends Module {
    private static final Component HEART = Component.text("❤", NamedTextColor.RED);
    private Scoreboard board;
    private Objective objective;
    /** The scoreboard each viewer had before they were moved onto {@link #board}. */
    private final Map<UUID, Scoreboard> previous = new HashMap<>();
    /** What each player's line says now, so scores only change when the text does. */
    private final Map<UUID, String> shown = new HashMap<>();

    public HealthTags(Contender plugin) { super(plugin); }

    @Override protected void onEnable() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("contender_health", Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        tasks.repeat(2L, 2L, this::update);
    }

    @Override protected void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getScoreboard() == board) player.setScoreboard(restore(player.getUniqueId()));
        previous.clear();
        shown.clear();
    }

    /** Health on a 0–20 scale: whole numbers, with one decimal place below 3. */
    static String format(double health, double max) {
        double value = Math.clamp(max <= 0 ? 0 : health / max * 20, 0, 20);
        return value < 3 ? String.format(Locale.ROOT, "%.1f", value) : Long.toString(Math.round(value));
    }

    private void update() {
        boolean moved = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            ActivityRegistry.Claim claim = plugin.getRegistry().claim(id);
            boolean views = claim != null && enabled(activityOf(id, claim));
            if (views && player.getScoreboard() != board) {
                previous.put(id, player.getScoreboard());
                player.setScoreboard(board);
                moved = true;
            } else if (!views && player.getScoreboard() == board) {
                player.setScoreboard(restore(id));
                moved = true;
            }
            boolean playing = views && claim.involvement() == ActivityRegistry.Involvement.PLAYING
                    && player.getGameMode() != GameMode.SPECTATOR && !player.isDead();
            var max = player.getAttribute(Attribute.MAX_HEALTH);
            String label = playing ? format(player.getHealth(), max == null ? 20 : max.getValue()) : "";
            if (label.equals(shown.get(id))) continue;
            shown.put(id, label);
            var score = objective.getScore(player);
            score.setScore(0);
            score.numberFormat(NumberFormat.fixed(label.isEmpty() ? Component.empty()
                    : Component.text(label + " ", NamedTextColor.WHITE).append(HEART)));
        }
        // Nametag teams live on each scoreboard, so add them to this one right away.
        if (moved) plugin.getNameTagManager().refresh();
    }

    /** The game a player is in, or the one they're watching. */
    private Activity activityOf(UUID id, ActivityRegistry.Claim claim) {
        if (claim.involvement() == ActivityRegistry.Involvement.WATCHING && plugin.getSpectate().target(id) instanceof Activity watched) return watched;
        return claim.activity();
    }

    private static boolean enabled(Activity activity) {
        Kit kit = activity == null ? null : activity.kit();
        return kit != null && kit.isHealthUnderName();
    }

    private Scoreboard restore(UUID id) {
        Scoreboard before = previous.remove(id);
        return before == null ? Bukkit.getScoreboardManager().getMainScoreboard() : before;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getScoreboard() == board) player.setScoreboard(restore(player.getUniqueId()));
        previous.remove(player.getUniqueId());
        shown.remove(player.getUniqueId());
        board.resetScores(player);
    }
}
