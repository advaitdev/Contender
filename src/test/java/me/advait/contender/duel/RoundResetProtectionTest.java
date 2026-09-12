package me.advait.contender.duel;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundResetProtectionTest {
    @Test void freezingStopsMomentumAndReleasingRestoresTheOriginalGravity() {
        var player = new FrozenPlayer(true);
        var protection = new RoundResetProtection();

        protection.freeze(player.player);

        assertFalse(player.gravity.get());
        assertEquals(1, player.tags.size());
        assertEquals((byte) 1, player.tags.values().iterator().next());
        verify(player.player).setVelocity(new Vector());
        verify(player.player).setFallDistance(0);
        protection.release(player.player);
        assertTrue(player.gravity.get());
        assertTrue(player.tags.isEmpty());
    }

    @Test void repeatedFreezingAndRespawnDoNotReplaceAnOriginallyDisabledGravityFlag() {
        var player = new FrozenPlayer(false);
        var protection = new RoundResetProtection();
        protection.freeze(player.player);
        player.gravity.set(true); // A respawn can reset the entity's physics flags.
        protection.freeze(player.player);

        protection.close();

        assertFalse(player.gravity.get());
        assertTrue(player.tags.isEmpty());
    }

    @Test void aRespawnedPlayerReferenceKeepsTheSameOriginalAndIsRestoredOnClose() {
        var before = new FrozenPlayer(true);
        var after = new FrozenPlayer(before.id, false, new HashMap<>(before.tags));
        var protection = new RoundResetProtection();
        protection.freeze(before.player);
        protection.freeze(after.player);

        protection.close();

        assertTrue(after.gravity.get());
        assertTrue(after.tags.isEmpty());
        verify(after.player).setGravity(true);
    }

    @Test void closeRestoresDisconnectedPlayersWithoutLookingThemUpAgain() {
        var player = new FrozenPlayer(true);
        var protection = new RoundResetProtection();
        protection.freeze(player.player);
        when(player.player.isOnline()).thenReturn(false);

        protection.close();

        assertTrue(player.gravity.get());
        assertTrue(player.tags.isEmpty());
        protection.close();
        verify(player.player).setGravity(true);
    }

    @Test void releasingAnObserverImmediatelyRestoresGravityAndCloseDoesNotTouchThemAgain() {
        var observer = new FrozenPlayer(true);
        var remaining = new FrozenPlayer(false);
        var protection = new RoundResetProtection();
        protection.freeze(observer.player);
        protection.freeze(remaining.player);

        protection.release(observer.player);
        assertTrue(observer.gravity.get());
        assertTrue(observer.tags.isEmpty());
        clearInvocations(observer.player);
        protection.close();

        verify(observer.player, never()).setGravity(anyBoolean());
        assertFalse(remaining.gravity.get());
        assertTrue(remaining.tags.isEmpty());
    }

    @Test void crashRecoveryUsesTheSavedOriginalInsteadOfTheCurrentGravity() {
        for (boolean original : new boolean[]{true, false}) {
            var before = new FrozenPlayer(original);
            new RoundResetProtection().freeze(before.player);
            var rejoined = new FrozenPlayer(before.id, false, new HashMap<>(before.tags));

            RoundResetProtection.recover(rejoined.player);

            assertEquals(original, rejoined.gravity.get());
            assertTrue(rejoined.tags.isEmpty());
            clearInvocations(rejoined.player);
            RoundResetProtection.recover(rejoined.player);
            verify(rejoined.player, never()).setGravity(anyBoolean());
        }
    }

    @Test void newProtectionUsesAnExistingCrashMarkerWithoutOverwritingIt() {
        var player = new FrozenPlayer(true);
        new RoundResetProtection().freeze(player.player);
        var next = new RoundResetProtection();

        next.freeze(player.player);
        next.close();

        assertTrue(player.gravity.get());
        assertTrue(player.tags.isEmpty());
    }

    @Test void joinListenerRecoversTaggedPlayersAndLeavesOtherPlayersAlone() {
        var player = new FrozenPlayer(true);
        new RoundResetProtection().freeze(player.player);
        var event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player.player);

        new RoundResetProtection.RecoveryListener().onJoin(event);

        assertTrue(player.gravity.get());
        assertTrue(player.tags.isEmpty());
        var unrelated = new FrozenPlayer(false);
        RoundResetProtection.recover(unrelated.player);
        verify(unrelated.player, never()).setGravity(anyBoolean());
    }

    @Test void cleanupContinuesAfterOneFailureAndKeepsItsMarkerForARetry() {
        var broken = new FrozenPlayer(true);
        var healthy = new FrozenPlayer(true);
        var protection = new RoundResetProtection();
        protection.freeze(broken.player);
        protection.freeze(healthy.player);
        doThrow(new IllegalStateException("Player unavailable")).when(broken.player).setGravity(true);

        var failure = assertThrows(IllegalStateException.class, protection::close);

        assertEquals(1, failure.getSuppressed().length);
        assertTrue(healthy.gravity.get());
        assertTrue(healthy.tags.isEmpty());
        assertFalse(broken.tags.isEmpty());
        doAnswer(call -> { broken.gravity.set(true); return null; }).when(broken.player).setGravity(true);
        protection.close();
        assertTrue(broken.gravity.get());
        assertTrue(broken.tags.isEmpty());
    }

    @Test void aPartiallyFailedFreezeCanStillBeReleased() {
        var player = new FrozenPlayer(true);
        var protection = new RoundResetProtection();
        doThrow(new IllegalStateException("Velocity failed")).when(player.player).setVelocity(any());

        assertThrows(IllegalStateException.class, () -> protection.freeze(player.player));
        protection.close();

        assertTrue(player.gravity.get());
        assertTrue(player.tags.isEmpty());
    }

    private static final class FrozenPlayer {
        final UUID id;
        final Player player = mock(Player.class);
        final AtomicBoolean gravity;
        final Map<NamespacedKey, Byte> tags;

        FrozenPlayer(boolean gravity) { this(UUID.randomUUID(), gravity, new HashMap<>()); }

        FrozenPlayer(UUID id, boolean original, Map<NamespacedKey, Byte> tags) {
            this.id = id;
            this.gravity = new AtomicBoolean(original);
            this.tags = tags;
            when(player.getUniqueId()).thenReturn(id);
            when(player.hasGravity()).thenAnswer(call -> gravity.get());
            doAnswer(call -> { gravity.set(call.getArgument(0)); return null; }).when(player).setGravity(anyBoolean());
            var data = mock(PersistentDataContainer.class);
            when(player.getPersistentDataContainer()).thenReturn(data);
            when(data.get(any(), eq(PersistentDataType.BYTE))).thenAnswer(call -> tags.get(call.getArgument(0)));
            doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; })
                    .when(data).set(any(), eq(PersistentDataType.BYTE), any());
            doAnswer(call -> { tags.remove(call.getArgument(0)); return null; }).when(data).remove(any());
        }
    }
}
