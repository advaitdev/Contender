package me.advait.contender.duel;

import me.advait.contender.Contender;
import me.advait.contender.kit.Kit;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.role.RoleManager;
import me.advait.contender.spectator.SpectatorVisibility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuelLobbyReturnTest {
    @Test void blockedReturnKeepsItsInventoryAndRetriesWithoutClearingPlayersAlreadyReturned() throws Exception {
        try (Fixture f = new Fixture()) {
            f.first.blocked = true;
            ItemStack duringDuel = f.first.storage[0];

            assertFalse(f.duel.tryReturnParticipantsToLobby());

            assertSame(duringDuel, f.first.storage[0]);
            assertTrue(f.duel.hasParticipant(f.first.id));
            verify(f.first.inventory, never()).clear();
            verify(f.first.inventory, never()).setStorageContents(any());
            f.assertRestored(f.second);
            verify(f.avatars(), never()).clear();

            ItemStack newLobbyItem = mock(ItemStack.class);
            f.second.storage[0] = newLobbyItem;
            f.first.blocked = false;

            assertTrue(f.duel.tryReturnParticipantsToLobby());
            assertTrue(f.duel.tryReturnParticipantsToLobby());

            f.assertRestored(f.first);
            assertSame(newLobbyItem, f.second.storage[0], "A later retry must preserve changes made after a successful return");
            verify(f.lobby, times(2)).sendToLobbyChecked(f.first.player);
            verify(f.lobby, times(1)).sendToLobbyChecked(f.second.player);
            verify(f.first.inventory, times(1)).clear();
            verify(f.second.inventory, times(1)).clear();
            verify(f.avatars(), times(1)).clear();
        }
    }

    @Test void deadPlayerWaitsForRespawnBeforeLobbyTransferAndInventoryRestoration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.first.dead = true;
            ItemStack duringDuel = f.first.storage[0];

            assertFalse(f.duel.tryReturnParticipantsToLobby());
            assertFalse(f.duel.tryReturnParticipantsToLobby());

            verify(f.lobby, never()).sendToLobbyChecked(f.first.player);
            verify(f.first.inventory, never()).clear();
            assertSame(duringDuel, f.first.storage[0]);
            assertTrue(f.duel.hasParticipant(f.first.id));
            f.first.dead = false;

            assertTrue(f.duel.tryReturnParticipantsToLobby());

            f.assertRestored(f.first);
            verify(f.lobby, times(1)).sendToLobbyChecked(f.first.player);
            verify(f.lobby, times(1)).sendToLobbyChecked(f.second.player);
        }
    }

    @Test void blockedSpectatorKeepsTheAvatarUntilConfirmedArrivalAndRoleIsAppliedAfterRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            f.duel.getSpectators().add(f.first.id);
            f.first.blocked = true;

            assertFalse(f.duel.tryReturnParticipantsToLobby());

            verify(f.avatars(), never()).remove(f.first.player);
            verify(f.avatars(), never()).clear();
            verify(f.roles, never()).applySpectatorRole(f.first.player);
            assertTrue(f.duel.isSpectator(f.first.id));
            f.first.blocked = false;
            clearInvocations(f.lobby, f.avatars(), f.roles);

            assertTrue(f.duel.tryReturnParticipantsToLobby());

            var order = inOrder(f.lobby, f.avatars(), f.roles, f.first.inventory);
            order.verify(f.lobby).sendToLobbyChecked(f.first.player);
            order.verify(f.first.inventory).clear();
            order.verify(f.avatars()).remove(f.first.player);
            order.verify(f.roles).applySpectatorRole(f.first.player);
            order.verify(f.first.inventory).setStorageContents(any());
        }
    }

    @Test void disconnectedPlayersDoNotPreventTheRemainingPlayersFromReturning() throws Exception {
        try (Fixture f = new Fixture()) {
            f.first.online = false;

            assertTrue(f.duel.tryReturnParticipantsToLobby());

            verify(f.lobby, never()).sendToLobbyChecked(f.first.player);
            f.assertRestored(f.second);
            verify(f.avatars()).clear();
        }
    }

    @Test void unexpectedReturnFailureStillReturnsOtherPlayersAndCanBeRetried() throws Exception {
        try (Fixture f = new Fixture()) {
            f.first.failure = new IllegalStateException("Teleport listener failed");

            IllegalStateException failure = assertThrows(IllegalStateException.class, f.duel::tryReturnParticipantsToLobby);

            assertSame(f.first.failure, failure.getSuppressed()[0]);
            f.assertRestored(f.second);
            verify(f.first.inventory, never()).clear();
            f.first.failure = null;

            assertTrue(f.duel.tryReturnParticipantsToLobby());

            f.assertRestored(f.first);
            verify(f.lobby, times(1)).sendToLobbyChecked(f.second.player);
        }
    }

    @Test void bestEffortVoidWrapperDoesNotMarkABlockedPlayerAsRestored() throws Exception {
        try (Fixture f = new Fixture()) {
            f.first.blocked = true;

            f.duel.returnParticipantsToLobby();

            verify(f.first.inventory, never()).clear();
            f.first.blocked = false;
            assertTrue(f.duel.tryReturnParticipantsToLobby());
            f.assertRestored(f.first);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Contender plugin = mock(Contender.class);
        final DuelManager manager = mock(DuelManager.class);
        final LobbyManager lobby = mock(LobbyManager.class);
        final RoleManager roles = mock(RoleManager.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<SpectatorVisibility> visibility = mockConstruction(SpectatorVisibility.class);
        final Map<UUID, TestPlayer> players = new HashMap<>();
        final TestPlayer first = new TestPlayer(), second = new TestPlayer();
        final Duel duel;

        Fixture() throws Exception {
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(plugin.getLobbyManager()).thenReturn(lobby);
            when(plugin.getRoleManager()).thenReturn(roles);
            players.put(first.id, first); players.put(second.id, second);
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> {
                TestPlayer player = players.get(call.getArgument(0));
                return player == null ? null : player.player;
            });
            when(lobby.sendToLobbyChecked(any())).thenAnswer(call -> {
                TestPlayer player = players.get(((Player) call.getArgument(0)).getUniqueId());
                if (player.failure != null) throw player.failure;
                if (player.blocked) return false;
                player.inventory.clear();
                return true;
            });
            var setup = new DuelSetup(UUID.randomUUID());
            setup.setSelectedMap(new ArenaMap("test"));
            setup.setSelectedKit(mock(Kit.class));
            setup.getTeam1().addPlayer(first.id); setup.getTeam2().addPlayer(second.id);
            duel = new Duel(plugin, manager, setup);
            when(manager.getDuel(any(UUID.class))).thenAnswer(call -> duel.getAllParticipants().contains(call.getArgument(0)) ? duel : null);
            saveSnapshot(first); saveSnapshot(second);
        }

        @SuppressWarnings("unchecked")
        private void saveSnapshot(TestPlayer player) throws Exception {
            // Seed the existing snapshot boundary without starting a duel or relying on a Minecraft item registry.
            Class<?> type = Class.forName(Duel.class.getName() + "$InventorySnapshot");
            var constructor = type.getDeclaredConstructor(ItemStack[].class, ItemStack[].class, ItemStack.class);
            constructor.setAccessible(true);
            Object snapshot = constructor.newInstance(new ItemStack[]{player.originalStorage},
                    new ItemStack[]{null, null, null, player.originalArmor}, player.originalOffhand);
            var field = Duel.class.getDeclaredField("preDuelInventories");
            field.setAccessible(true);
            ((Map<UUID, Object>) field.get(duel)).put(player.id, snapshot);
        }

        SpectatorVisibility avatars() { return visibility.constructed().getFirst(); }

        void assertRestored(TestPlayer player) {
            assertArrayEquals(new ItemStack[]{player.restoredStorage}, player.storage);
            assertArrayEquals(new ItemStack[]{null, null, null, player.restoredArmor}, player.armor);
            assertSame(player.restoredOffhand, player.offhand);
            verify(player.player).updateInventory();
        }

        @Override public void close() { visibility.close(); bukkit.close(); }
    }

    private static final class TestPlayer {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final ItemStack restoredStorage = mock(ItemStack.class), restoredArmor = mock(ItemStack.class), restoredOffhand = mock(ItemStack.class);
        final ItemStack originalStorage = copiedTo(restoredStorage), originalArmor = copiedTo(restoredArmor), originalOffhand = copiedTo(restoredOffhand);
        ItemStack[] storage = {mock(ItemStack.class)}, armor = {null, null, null, mock(ItemStack.class)};
        ItemStack offhand = mock(ItemStack.class);
        boolean online = true, dead, blocked;
        RuntimeException failure;

        TestPlayer() {
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenAnswer(call -> online);
            when(player.isDead()).thenAnswer(call -> dead);
            when(player.getInventory()).thenReturn(inventory);
            doAnswer(call -> { storage = new ItemStack[36]; armor = new ItemStack[4]; offhand = null; return null; }).when(inventory).clear();
            doAnswer(call -> { storage = ((ItemStack[]) call.getArgument(0)).clone(); return null; }).when(inventory).setStorageContents(any());
            doAnswer(call -> { armor = ((ItemStack[]) call.getArgument(0)).clone(); return null; }).when(inventory).setArmorContents(any());
            doAnswer(call -> { offhand = call.getArgument(0); return null; }).when(inventory).setItemInOffHand(any());
        }

        private static ItemStack copiedTo(ItemStack restored) {
            ItemStack original = mock(ItemStack.class), snapshot = mock(ItemStack.class);
            when(original.clone()).thenReturn(snapshot);
            when(snapshot.clone()).thenReturn(restored);
            return original;
        }
    }
}
