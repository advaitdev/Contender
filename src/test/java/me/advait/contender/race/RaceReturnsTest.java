package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.role.RoleManager;
import me.advait.contender.util.YamlStorage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceReturnsTest {
    @TempDir Path directory;
    @Test void snapshotSurvivesRestartAndBlockedTeleportUntilRestorationSucceeds() {
        Contender plugin = mock(Contender.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var lobby = mock(LobbyManager.class); var roles = mock(RoleManager.class);
        when(plugin.getLobbyManager()).thenReturn(lobby); when(plugin.getRoleManager()).thenReturn(roles);
        var location = new Location(mock(World.class), 1, 70, 1); when(lobby.getLobbyLocation()).thenReturn(location);
        Player player = mock(Player.class); UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        PlayerInventory inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[41]); when(inventory.getHeldItemSlot()).thenReturn(5);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE); when(player.getHealth()).thenReturn(17d); when(player.getMaxHealth()).thenReturn(20d);
        when(player.getFoodLevel()).thenReturn(14); when(player.getSaturation()).thenReturn(3f); when(player.getLevel()).thenReturn(8); when(player.getExp()).thenReturn(.3f);
        when(player.getAllowFlight()).thenReturn(true); when(player.isFlying()).thenReturn(true);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        var original = new RaceReturns(plugin); original.capture(player);
        assertTrue(original.pending(id)); verify(inventory, never()).clear();
        var restarted = new RaceReturns(plugin); assertTrue(restarted.pending(id));
        when(player.teleport(location)).thenReturn(false); assertFalse(restarted.restore(player)); assertTrue(restarted.pending(id));
        verify(inventory, never()).setContents(any());
        when(player.teleport(location)).thenReturn(true); assertTrue(restarted.restore(player));
        verify(inventory).setContents(argThat(items -> items.length == 41)); verify(inventory).setHeldItemSlot(5);
        verify(player).setGameMode(GameMode.CREATIVE); verify(player).setHealth(17); verify(player).setFoodLevel(14);
        verify(player).setLevel(8); verify(player).setExp(.3f); verify(player).setFlying(true);
        assertFalse(new RaceReturns(plugin).pending(id));
    }

    @Test void originalCursorIsSettledBeforeItsInventorySnapshot() {
        var fixture = fixture();
        ItemStack original = mock(ItemStack.class), saved = mock(ItemStack.class);
        when(original.clone()).thenReturn(saved);
        fixture.cursor.set(original);
        doAnswer(call -> {
            fixture.items[9] = fixture.cursor.getAndSet(null);
            return null;
        }).when(fixture.player).closeInventory();

        try (var storage = mockStatic(YamlStorage.class)) {
            RaceReturns returns = new RaceReturns(fixture.plugin);
            returns.capture(fixture.player);
            assertNull(fixture.cursor.get());
            fixture.items[9] = null;
            assertTrue(returns.restore(fixture.player));
            verify(fixture.inventory).setContents(argThat(items -> items[9] == saved));
            var order = inOrder(fixture.player, fixture.inventory);
            order.verify(fixture.player).closeInventory();
            order.verify(fixture.inventory).getContents();
        }
    }

    @Test void raceCursorIsClearedBeforeClosingAndRestoringTheOriginalLoadout() {
        var fixture = fixture();
        try (var storage = mockStatic(YamlStorage.class)) {
            RaceReturns returns = new RaceReturns(fixture.plugin);
            returns.capture(fixture.player);
            ItemStack raceMace = mock(ItemStack.class);
            fixture.cursor.set(raceMace);
            clearInvocations(fixture.player, fixture.inventory);

            assertTrue(returns.restore(fixture.player));

            assertNull(fixture.cursor.get());
            var order = inOrder(fixture.player, fixture.inventory);
            order.verify(fixture.player).setItemOnCursor(null);
            order.verify(fixture.player).closeInventory();
            order.verify(fixture.player).teleport(fixture.lobby);
            order.verify(fixture.inventory).setContents(any());
            assertFalse(returns.pending(fixture.player.getUniqueId()));
        }
    }

    @Test void blockedReturnKeepsTheRaceCursorUntilTheSuccessfulRetry() {
        var fixture = fixture();
        try (var storage = mockStatic(YamlStorage.class)) {
            RaceReturns returns = new RaceReturns(fixture.plugin);
            returns.capture(fixture.player);
            ItemStack raceBed = mock(ItemStack.class);
            fixture.cursor.set(raceBed);
            when(fixture.player.teleport(fixture.lobby)).thenReturn(false);

            assertFalse(returns.restore(fixture.player));
            assertSame(raceBed, fixture.cursor.get());
            assertTrue(returns.pending(fixture.player.getUniqueId()));
            verify(fixture.inventory, never()).setContents(any());

            when(fixture.player.teleport(fixture.lobby)).thenReturn(true);
            assertTrue(returns.restore(fixture.player));
            assertNull(fixture.cursor.get());
            assertFalse(returns.pending(fixture.player.getUniqueId()));
        }
    }

    @Test void unrelatedAndDeadPlayersKeepTheirInventoryViewsAndCursors() {
        var fixture = fixture();
        try (var storage = mockStatic(YamlStorage.class)) {
            RaceReturns returns = new RaceReturns(fixture.plugin);
            ItemStack original = mock(ItemStack.class);
            fixture.cursor.set(original);

            assertTrue(returns.restore(fixture.player));
            verify(fixture.player, never()).closeInventory();
            verify(fixture.player, never()).setItemOnCursor(any());
            assertSame(original, fixture.cursor.get());

            returns.capture(fixture.player);
            clearInvocations(fixture.player);
            when(fixture.player.isDead()).thenReturn(true);
            assertFalse(returns.restore(fixture.player));
            verify(fixture.player, never()).closeInventory();
            verify(fixture.player, never()).setItemOnCursor(any());
            assertSame(original, fixture.cursor.get());
            assertTrue(returns.pending(fixture.player.getUniqueId()));
        }
    }

    @Test void rejectedCapturesDoNotCloseTheInventoryOrReplaceTheirSnapshot() {
        var fixture = fixture();
        try (var storage = mockStatic(YamlStorage.class)) {
            RaceReturns returns = new RaceReturns(fixture.plugin);
            when(fixture.player.isDead()).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> returns.capture(fixture.player));
            verify(fixture.player, never()).closeInventory();
            assertFalse(returns.pending(fixture.player.getUniqueId()));

            when(fixture.player.isDead()).thenReturn(false);
            returns.capture(fixture.player);
            clearInvocations(fixture.player, fixture.inventory);
            assertThrows(IllegalStateException.class, () -> returns.capture(fixture.player));
            verify(fixture.player, never()).closeInventory();
            verify(fixture.inventory, never()).getContents();
            assertTrue(returns.pending(fixture.player.getUniqueId()));
        }
    }

    private record Fixture(Contender plugin, Player player, PlayerInventory inventory, Location lobby,
                           ItemStack[] items, AtomicReference<ItemStack> cursor) { }

    private Fixture fixture() {
        Contender plugin = mock(Contender.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var lobbyManager = mock(LobbyManager.class);
        when(plugin.getLobbyManager()).thenReturn(lobbyManager);
        when(plugin.getRoleManager()).thenReturn(mock(RoleManager.class));
        Location lobby = new Location(mock(World.class), 1, 70, 1);
        when(lobbyManager.getLobbyLocation()).thenReturn(lobby);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Racer");
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getHealth()).thenReturn(20d);
        when(player.getMaxHealth()).thenReturn(20d);
        when(player.teleport(lobby)).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack[] items = new ItemStack[41];
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(items);
        AtomicReference<ItemStack> cursor = new AtomicReference<>();
        when(player.getItemOnCursor()).thenAnswer(call -> cursor.get());
        doAnswer(call -> { cursor.set(call.getArgument(0)); return null; }).when(player).setItemOnCursor(any());
        return new Fixture(plugin, player, inventory, lobby, items, cursor);
    }
}
