package me.advait.contender.player;

import me.advait.contender.tournament.RosterParser.PlayerIdentity;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Call on the server thread; uncached profiles are resolved asynchronously by Paper. */
public final class PlayerIdentityResolver {
    private PlayerIdentityResolver() { }

    public static CompletableFuture<PlayerIdentity> resolve(String name) {
        OfflinePlayer known = Bukkit.getPlayerExact(name);
        if (known == null) known = Bukkit.getOfflinePlayerIfCached(name);
        if (known != null) {
            return CompletableFuture.completedFuture(new PlayerIdentity(known.getUniqueId(),
                    known.getName() == null ? name : known.getName()));
        }
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Enter a Minecraft username for " + name + ".");
        }
        // Paper uses the server's authentication/proxy settings when completing a profile.
        // A missing online-mode profile must never become a made-up offline UUID.
        return Bukkit.createProfileExact(null, name).update().orTimeout(30, TimeUnit.SECONDS)
                .handle((profile, failure) -> {
                    if (failure != null || profile == null || profile.getId() == null
                            || profile.getName() == null || profile.getName().isBlank()) {
                        throw new IllegalArgumentException("Couldn't look up " + name + ". Check the name and try again.", failure);
                    }
                    return new PlayerIdentity(profile.getId(), profile.getName());
                });
    }
}
