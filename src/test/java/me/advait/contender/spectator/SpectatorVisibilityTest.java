package me.advait.contender.spectator;

import me.advait.contender.Contender;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.nametag.NameTagManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.role.PlayerRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpectatorVisibilityTest {
    private final Contender plugin = mock(Contender.class);
    private final World world = mock(World.class);
    private final Server server = mock(Server.class);
    private final SpectatorVisibility visibility = new SpectatorVisibility(plugin);
    private final List<Player> online = new ArrayList<>();
    private final Map<Player, AllayAvatar> avatars = new HashMap<>();
    SpectatorVisibilityTest() {
        when(plugin.getServer()).thenReturn(server);
        doReturn(online).when(server).getOnlinePlayers();
        when(plugin.getDuelManager()).thenReturn(mock(DuelManager.class));
        RoleManager roles = mock(RoleManager.class); when(plugin.getRoleManager()).thenReturn(roles);
        when(roles.getRole(any())).thenReturn(PlayerRole.CONTESTANT);
        NameTagManager tags = mock(NameTagManager.class); when(plugin.getNameTagManager()).thenReturn(tags);
        when(tags.displayName(any(Player.class))).thenReturn(Component.text("Spectator"));
        when(plugin.createSpectatorAvatar(any())).thenAnswer(call -> avatars.get(call.getArgument(0)));
    }
    private Player player(double x) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(player.isCollidable()).thenReturn(true); when(player.getCanPickupItems()).thenReturn(true);
        when(player.getAffectsSpawning()).thenReturn(true);
        online.add(player); avatars.put(player, mock(AllayAvatar.class));
        return player;
    }
    @Test void ownerSeesTheirOwnAllayEvenWithAnInvisibleKitAndItFollowsTheirMovement() {
        Player spectator = player(0);
        AllayAvatar avatar = avatars.get(spectator);
        visibility.update(List.of(), List.of(spectator), true);
        verify(avatar).spawn(eq(spectator), eq(new Location(world, 0, 66, 0)), any());
        when(spectator.getLocation()).thenReturn(new Location(world, 12, 80, 4, 90, 30));
        visibility.update(List.of(), List.of(spectator), true);
        verify(avatar).move(eq(spectator), eq(new Location(world, 12, 82, 4, 90, 30)), any());

        visibility.update(List.of(), List.of(), true);
        verify(avatar).destroy(spectator);
        verify(spectator, never()).hidePlayer(any(), any());
        verify(spectator, never()).showPlayer(any(), any());
        verify(spectator).setInvisible(false);
    }
    @Test void bodyStaysHiddenAndOnlyTheAllayChangesAtTheDistanceBoundary() {
        Player near = player(0), far = player(60), spectator = player(9.9);
        AllayAvatar avatar = avatars.get(spectator);
        visibility.update(List.of(near, far), List.of(spectator), false);
        verify(near).hidePlayer(plugin, spectator); verify(far).hidePlayer(plugin, spectator);
        verify(avatar, never()).spawn(eq(near), any(), any());
        verify(avatar).spawn(eq(far), argThat(location -> location.getY() == 66), any());
        when(spectator.getLocation()).thenReturn(new Location(world, 10, 64, 0));
        visibility.update(List.of(near, far), List.of(spectator), false);
        verify(avatar).spawn(eq(near), any(), any());
        when(spectator.getLocation()).thenReturn(new Location(world, 55, 64, 0));
        visibility.update(List.of(near, far), List.of(spectator), false);
        verify(avatar).destroy(far); verify(avatar).move(eq(near), any(), any());
        verify(near, never()).showPlayer(any(), any()); verify(spectator, never()).setVelocity(any());
    }
    @Test void otherSpectatorsCanSeeAllaysEvenWithAnInvisibleKit() {
        Player viewer = player(0), spectator = player(100), other = player(101);
        visibility.update(List.of(viewer), List.of(spectator, other), true);
        verify(avatars.get(spectator), never()).spawn(eq(viewer), any(), any());
        verify(avatars.get(spectator)).spawn(eq(other), any(), any());
        visibility.update(List.of(viewer), List.of(spectator, other), false);
        verify(avatars.get(spectator)).spawn(eq(viewer), any(), any());
    }
    @Test void changingWorldsDestroysTheOldAvatarAndRestoresItOnReturn() {
        Player viewer = player(0), spectator = player(100);
        visibility.update(List.of(viewer), List.of(spectator), false);
        World elsewhere = mock(World.class);
        when(spectator.getWorld()).thenReturn(elsewhere); when(spectator.getLocation()).thenReturn(new Location(elsewhere, 0, 64, 0));
        visibility.update(List.of(viewer), List.of(spectator), false);
        verify(avatars.get(spectator)).destroy(viewer);
        verify(avatars.get(spectator)).destroy(spectator);
        verify(avatars.get(spectator)).spawn(eq(spectator), argThat(location -> location.getWorld().equals(elsewhere)), any());
        when(spectator.getWorld()).thenReturn(world); when(spectator.getLocation()).thenReturn(new Location(world, 100, 64, 0));
        visibility.update(List.of(viewer), List.of(spectator), false);
        verify(avatars.get(spectator), times(2)).spawn(eq(viewer), any(), any());
        verify(avatars.get(spectator), times(3)).spawn(eq(spectator), any(), any());
        verify(avatars.get(spectator), times(2)).destroy(spectator);
    }
    @Test void leavingRestoresBodyAndFlagsOnceAndRemovesClientEntity() {
        Player viewer = player(0), spectator = player(100);
        visibility.update(List.of(viewer), List.of(spectator), false);
        verify(spectator).setCollidable(false); verify(spectator).setCanPickupItems(false);
        verify(spectator).setInvisible(true); verify(spectator).setFlying(true);
        visibility.clear(); visibility.clear();
        verify(avatars.get(spectator)).destroy(viewer); verify(viewer).showPlayer(plugin, spectator);
        verify(avatars.get(spectator)).destroy(spectator);
        verify(spectator).setCollidable(true); verify(spectator).setCanPickupItems(true);
        verify(spectator).setInvisible(false); verify(spectator).setAllowFlight(false);
    }
    @Test void disconnectCleansTheAvatarWithoutSendingToTheDisconnectedViewer() {
        Player viewer = player(0), spectator = player(100);
        visibility.update(List.of(viewer), List.of(spectator), false);
        when(spectator.isOnline()).thenReturn(false);
        visibility.update(List.of(viewer), List.of(), false);
        verify(avatars.get(spectator)).destroy(viewer); verify(viewer).showPlayer(plugin, spectator);
        verify(avatars.get(spectator), never()).destroy(spectator);
    }
}
