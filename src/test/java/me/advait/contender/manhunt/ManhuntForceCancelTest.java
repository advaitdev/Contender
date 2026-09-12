package me.advait.contender.manhunt;

import me.advait.contender.Contender;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.minigame.PlayerReturnStore;
import me.advait.contender.role.RoleManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManhuntForceCancelTest {
    @TempDir Path folder;

    @Test void interruptedSessionStopsAndPreparationIsCancelledWithoutDeletingTheWorld() throws Exception {
        try (var f = new Fixture()) {
            f.run.end(ManhuntRun.State.INTERRUPTED);
            var session = mock(ManhuntSession.class); set(f.manager, "session", session);
            when(f.world.state()).thenReturn(ManhuntWorld.State.USED);
            f.manager.forceCancel();
            assertFalse(f.manager.active()); assertFalse(f.manager.busy()); assertEquals(ManhuntRun.State.CANCELLED, f.run.state());
            verify(session).disable(); verify(f.world).cancelPreparation(); verify(f.world).freeze();
            verify(f.world, never()).close(); verify(f.world, never()).prepare(any());
            assertEquals(ManhuntRun.State.CANCELLED, new ManhuntStore(folder.toFile()).load().state());
            f.manager.forceCancel(); verify(session, times(1)).disable();
        }
    }

    @Test void failedRestoresKeepSnapshotsAndObserversReturnWithoutInventoryChanges() throws Exception {
        try (var f = new Fixture()) {
            Player online = f.player("Restored"), blocked = f.player("Blocked"), offline = f.player("Offline"), observer = f.player("Observer");
            var returns = (PlayerReturnStore) field(f.manager, "returns").get(f.manager);
            returns.capture(online); returns.capture(blocked); returns.capture(offline);
            when(online.teleport(f.lobby)).thenReturn(true);
            when(f.world.contains(any())).thenReturn(true);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online, blocked, observer));
            f.run.end(ManhuntRun.State.INTERRUPTED);
            f.manager.forceCancel();
            assertFalse(f.manager.pendingReturn(online.getUniqueId()));
            assertTrue(f.manager.pendingReturn(blocked.getUniqueId())); assertTrue(f.manager.pendingReturn(offline.getUniqueId()));
            verify(observer).teleport(f.lobby); verify(observer.getInventory(), never()).setContents(any());
            verify(blocked.getInventory(), never()).setContents(any());
            assertTrue(new PlayerReturnStore(f.plugin, "manhunt-returns.yml").pending(blocked.getUniqueId()));
        }
    }

    @Test void normalFinishRetriesPendingReturnsEvenWhenTheResultIsAlreadyInterrupted() throws Exception {
        try (var f = new Fixture()) {
            Player player = f.player("Racer");
            ((PlayerReturnStore) field(f.manager, "returns").get(f.manager)).capture(player);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            f.run.end(ManhuntRun.State.INTERRUPTED);
            f.manager.finish(ManhuntRun.State.CANCELLED, false);
            verify(player).teleport(f.lobby); assertTrue(f.manager.pendingReturn(player.getUniqueId()));
        }
    }

    @Test void sessionCleanupFailureDoesNotSkipStoppingPreparationOrFreezing() throws Exception {
        try (var f = new Fixture()) {
            var session = mock(ManhuntSession.class); set(f.manager, "session", session);
            doThrow(new IllegalStateException("restore failed")).when(session).disable();
            assertThrows(IllegalStateException.class, f.manager::forceCancel);
            assertFalse(f.manager.active()); verify(f.world).cancelPreparation(); verify(f.world).freeze();
        }
    }

    private final class Fixture implements AutoCloseable {
        final Contender plugin = mock(Contender.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<ManhuntWorld> worlds;
        final Location lobby = new Location(mock(World.class), 0, 70, 0);
        final ManhuntRun run = new ManhuntRun(UUID.randomUUID(), "Manhunt", "kit", "end", List.of(
                new ManhuntRun.Entry(UUID.randomUUID(), "Runner", ManhuntRun.Team.RUNNER),
                new ManhuntRun.Entry(UUID.randomUUID(), "Hunter", ManhuntRun.Team.HUNTER)), 3);
        final ManhuntManager manager;
        final ManhuntWorld world;
        Fixture() throws Exception {
            when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(plugin.getRoleManager()).thenReturn(mock(RoleManager.class));
            var lobbies = mock(LobbyManager.class); when(plugin.getLobbyManager()).thenReturn(lobbies); when(lobbies.getLobbyLocation()).thenReturn(lobby);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            worlds = mockConstruction(ManhuntWorld.class, (mock, context) -> when(mock.state()).thenReturn(ManhuntWorld.State.READY));
            manager = new ManhuntManager(plugin); world = worlds.constructed().getFirst(); set(manager, "current", run);
        }
        Player player(String name) {
            var player = mock(Player.class); var inventory = mock(PlayerInventory.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn(name);
            when(player.getInventory()).thenReturn(inventory); when(inventory.getContents()).thenReturn(new ItemStack[41]);
            when(player.getGameMode()).thenReturn(GameMode.CREATIVE); when(player.getHealth()).thenReturn(20d); when(player.getMaxHealth()).thenReturn(20d);
            when(player.getActivePotionEffects()).thenReturn(List.of()); when(player.getLocation()).thenReturn(lobby.clone());
            return player;
        }
        public void close() { worlds.close(); bukkit.close(); }
    }
    private static Field field(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
}
