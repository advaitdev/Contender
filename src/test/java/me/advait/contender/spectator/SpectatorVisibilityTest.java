package me.advait.contender.spectator;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpectatorVisibilityTest {
    private final Plugin plugin = mock(Plugin.class);
    private final World world = mock(World.class);
    private final SpectatorVisibility visibility = new SpectatorVisibility(plugin);

    private Player player(double x) {
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, x, 64, 0));
        return player;
    }

    @Test
    void hidesPerContestantAndRestoresAtTheDistanceBoundaryWithoutPushing() {
        Player near = player(0);
        Player far = player(60);
        Player spectator = player(19.9);
        visibility.update(List.of(near, far), List.of(spectator), false);
        visibility.update(List.of(near, far), List.of(spectator), false);
        verify(near, times(1)).hidePlayer(plugin, spectator);
        verify(far, never()).hidePlayer(any(), any());
        verify(spectator, never()).setVelocity(any());

        when(spectator.getLocation()).thenReturn(new Location(world, 20, 64, 0));
        visibility.update(List.of(near, far), List.of(spectator), false);
        verify(near).showPlayer(plugin, spectator);

        when(spectator.getLocation()).thenReturn(new Location(world, 55, 64, 0));
        visibility.update(List.of(near, far), List.of(spectator), false);
        verify(far).hidePlayer(plugin, spectator);
        verify(near, times(1)).showPlayer(plugin, spectator);
    }

    @Test
    void switchingWorldsRestoresVisibilityWithoutComparingCrossWorldDistances() {
        Player viewer = player(0);
        Player spectator = player(0);
        visibility.update(List.of(viewer), List.of(spectator), false);
        when(spectator.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
        visibility.update(List.of(viewer), List.of(spectator), false);
        verify(viewer).showPlayer(plugin, spectator);
    }

    @Test
    void alwaysInvisibleKitOverridesDistanceAndCanBeTurnedOff() {
        Player viewer = player(0);
        Player spectator = player(100);
        Player otherSpectator = player(101);
        visibility.update(List.of(viewer), List.of(spectator, otherSpectator), true);
        verify(viewer).hidePlayer(plugin, spectator);
        verify(viewer).hidePlayer(plugin, otherSpectator);
        verify(spectator, never()).hidePlayer(any(), any());
        verify(otherSpectator, never()).hidePlayer(any(), any());
        visibility.update(List.of(viewer), List.of(spectator, otherSpectator), false);
        verify(viewer).showPlayer(plugin, spectator);
        verify(viewer).showPlayer(plugin, otherSpectator);
    }

    @Test
    void restoresVisibilityWhenAHiddenSpectatorLeavesAndOnCleanup() {
        Player viewer = player(0);
        Player departing = player(1);
        Player remaining = player(2);
        visibility.update(List.of(viewer), List.of(departing, remaining), false);
        visibility.update(List.of(viewer), List.of(remaining), false);
        verify(viewer).showPlayer(plugin, departing);
        verify(viewer, never()).showPlayer(plugin, remaining);
        visibility.clear();
        visibility.clear();
        verify(viewer, times(1)).showPlayer(plugin, remaining);
        verify(viewer, times(1)).showPlayer(plugin, departing);
    }
}
