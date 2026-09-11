package me.advait.contender.player;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerIdentityResolverTest {
    @Test void cachedOfflinePlayersKeepTheirUuidWithoutANetworkLookup() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            OfflinePlayer cached = mock(OfflinePlayer.class);
            UUID id = UUID.randomUUID();
            when(cached.getUniqueId()).thenReturn(id);
            when(cached.getName()).thenReturn("Alice");
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("alice")).thenReturn(cached);

            var identity = PlayerIdentityResolver.resolve("alice").join();

            assertEquals(id, identity.id());
            assertEquals("Alice", identity.name());
            bukkit.verify(() -> Bukkit.createProfileExact(null, "alice"), never());
            bukkit.verify(() -> Bukkit.getOfflinePlayer("alice"), never());
        }
    }

    @Test void anOnlineIdentityTakesPriorityOverTheCache() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Player player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Alice");
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(player);

            assertEquals(id, PlayerIdentityResolver.resolve("Alice").join().id());
            bukkit.verify(() -> Bukkit.getOfflinePlayerIfCached("Alice"), never());
        }
    }

    @Test void aPlayerWhoHasNeverJoinedIsResolvedWithoutBlocking() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PlayerProfile initial = mock(PlayerProfile.class);
            CompletableFuture<PlayerProfile> lookup = new CompletableFuture<>();
            when(initial.update()).thenReturn(lookup);
            bukkit.when(() -> Bukkit.createProfileExact(null, "alice")).thenReturn(initial);

            var result = PlayerIdentityResolver.resolve("alice");
            assertFalse(result.isDone());
            PlayerProfile resolved = mock(PlayerProfile.class);
            UUID id = UUID.randomUUID();
            when(resolved.getId()).thenReturn(id);
            when(resolved.getName()).thenReturn("Alice");
            lookup.complete(resolved);

            assertEquals(id, result.join().id());
            assertEquals("Alice", result.join().name());
        }
    }

    @Test void failedAndIncompleteLookupsNeverInventAnIdentity() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PlayerProfile profile = mock(PlayerProfile.class);
            bukkit.when(() -> Bukkit.createProfileExact(null, "Missing")).thenReturn(profile);
            when(profile.getName()).thenReturn("Missing");
            when(profile.update()).thenReturn(CompletableFuture.completedFuture(profile));
            var incomplete = assertThrows(CompletionException.class, () -> PlayerIdentityResolver.resolve("Missing").join());
            assertTrue(incomplete.getCause().getMessage().contains("Couldn't look up Missing"));

            when(profile.update()).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));
            var timeout = assertThrows(CompletionException.class, () -> PlayerIdentityResolver.resolve("Missing").join());
            assertTrue(timeout.getCause().getMessage().contains("Check the name and try again"));
            bukkit.verify(() -> Bukkit.getOfflinePlayer("Missing"), never());
        }
    }
}
