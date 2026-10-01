package me.advait.contender.activity;

import me.advait.contender.Contender;
import me.advait.contender.core.Module;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.logging.Level;

/** Which activity owns each player. Main thread only. */
public final class ActivityRegistry extends Module {
    public enum Involvement { PLAYING, WATCHING }
    public record Claim(Activity activity, Involvement involvement) { }

    private final Map<UUID, Claim> claims = new java.util.concurrent.ConcurrentHashMap<>();

    public ActivityRegistry(Contender plugin) { super(plugin); }

    /** Fails if another activity already owns the player. Re-claiming by the same activity updates the involvement. */
    public void claim(UUID player, Activity activity, Involvement involvement) {
        Claim existing = claims.get(player);
        if (existing != null && existing.activity() != activity) {
            throw new IllegalStateException(name(player) + " is busy in " + existing.activity().displayName() + ".");
        }
        claims.put(player, new Claim(activity, involvement));
    }

    public void release(UUID player, Activity activity) {
        Claim existing = claims.get(player);
        if (existing != null && existing.activity() == activity) claims.remove(player);
    }

    public void releaseAll(Activity activity) {
        claims.values().removeIf(claim -> claim.activity() == activity);
    }

    public Claim claim(UUID player) { return claims.get(player); }

    public Activity owner(UUID player) {
        Claim claim = claims.get(player);
        return claim == null ? null : claim.activity();
    }

    public boolean isFree(UUID player) { return !claims.containsKey(player); }

    public boolean isPlaying(UUID player) {
        Claim claim = claims.get(player);
        return claim != null && claim.involvement() == Involvement.PLAYING;
    }

    public boolean isWatching(UUID player) {
        Claim claim = claims.get(player);
        return claim != null && claim.involvement() == Involvement.WATCHING;
    }

    public Set<UUID> members(Activity activity) {
        Set<UUID> members = new HashSet<>();
        claims.forEach((id, claim) -> { if (claim.activity() == activity) members.add(id); });
        return members;
    }

    public Set<Activity> activities() {
        Set<Activity> activities = Collections.newSetFromMap(new IdentityHashMap<>());
        claims.values().forEach(claim -> activities.add(claim.activity()));
        return activities;
    }

    /** Stops every activity. Used by /cancelall and on shutdown. */
    public List<String> forceStopAll(String reason) {
        List<String> failures = new ArrayList<>();
        for (Activity activity : List.copyOf(activities())) {
            try { activity.forceStop(reason); }
            catch (RuntimeException | LinkageError failure) {
                failures.add(activity.displayName());
                plugin.getLogger().log(Level.SEVERE, "Could not stop " + activity.displayName(), failure);
            }
            releaseAll(activity);
        }
        return failures;
    }

    private static String name(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null) return player.getName();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "That player" : name;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Activity owner = owner(event.getPlayer().getUniqueId());
        if (owner == null) return;
        try { owner.handleQuit(event.getPlayer()); }
        catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, owner.displayName() + " could not handle " + event.getPlayer().getName() + " leaving", failure);
        }
    }

    /** Someone who was on the death screen when their game ended gets their items back as they respawn. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isFree(player.getUniqueId()) || !plugin.getSnapshots().has(player.getUniqueId())) return;
        var lobby = plugin.getLobby().location();
        if (lobby != null) event.setRespawnLocation(lobby);
        tasks.later(1, () -> {
            if (player.isOnline() && isFree(player.getUniqueId()) && plugin.getSnapshots().has(player.getUniqueId())) plugin.getSnapshots().restoreToLobby(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Run after other join handlers have set up the player (nametags, roles, lobby placement).
        tasks.later(2, () -> {
            if (!player.isOnline()) return;
            Activity owner = owner(player.getUniqueId());
            if (owner != null) {
                try { owner.handleJoin(player); }
                catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.SEVERE, owner.displayName() + " could not handle " + player.getName() + " returning", failure);
                }
            } else if (plugin.getSnapshots().has(player.getUniqueId())) {
                plugin.getSnapshots().restoreToLobby(player);
            }
        });
    }
}
