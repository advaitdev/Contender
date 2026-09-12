package me.advait.contender.combo;

import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.arena.ArenaManager;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.minigame.MinigameManager;
import me.advait.contender.minigame.PlayerReturnStore;
import me.advait.contender.testutil.StateTestServer;
import me.advait.contender.tournament.TournamentManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComboRecoveryTest {
    @TempDir Path folder;
    private StateTestServer server;
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<PlayerReturnStore> returnStores;
    private final List<Player> online = new ArrayList<>();
    private ComboManager manager;
    private ArenaManager arenas;
    private PlayerReturnStore returns;
    private World world;
    private Location lobby;

    @BeforeEach void setup() {
        server = new StateTestServer();
        when(server.plugin.getDataFolder()).thenReturn(folder.toFile());
        when(server.plugin.getLogger()).thenReturn(mock(Logger.class));
        arenas = mock(ArenaManager.class);
        when(server.plugin.getArenaManager()).thenReturn(arenas);
        when(arenas.reset(any())).thenReturn(CompletableFuture.completedFuture(null));
        world = mock(World.class); when(world.getName()).thenReturn("arenas");
        var lobbyWorld = mock(World.class); when(lobbyWorld.getName()).thenReturn("lobby");
        lobby = new Location(lobbyWorld, 0, 64, 0);
        var lobbies = mock(LobbyManager.class); when(lobbies.getLobbyLocation()).thenReturn(lobby);
        when(server.plugin.getLobbyManager()).thenReturn(lobbies);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.copyOf(online));
        returnStores = mockConstruction(PlayerReturnStore.class);
        manager = new ComboManager(server.plugin);
        returns = returnStores.constructed().getFirst();
    }

    @AfterEach void close() { returnStores.close(); bukkit.close(); server.close(); }

    @Test void forceCancelClearsInterruptedSessionAndIsIdempotent() throws Exception {
        ComboRun run = install(ComboRun.State.INTERRUPTED);
        ComboSession session = session(0);
        field("session", session);
        assertTrue(manager.busy());

        manager.forceCancel();
        manager.forceCancel();

        assertFalse(manager.busy()); assertFalse(manager.active());
        assertEquals(ComboRun.State.CANCELLED, run.state());
        assertEquals(ComboRun.State.CANCELLED, new ComboStore(folder.toFile()).load().state());
        verify(session).disable();
        verify(arenas).abandon(session.lease());
        verify(arenas, never()).reset(any());
    }

    @Test void ordinaryEndStillCleansAnAlreadyInterruptedSession() throws Exception {
        install(ComboRun.State.INTERRUPTED);
        ComboSession session = session(0); field("session", session);
        manager.end(ComboRun.State.CANCELLED);
        assertFalse(manager.active()); assertFalse(manager.busy());
        verify(session).disable(); verify(arenas).reset(session.lease()); verify(arenas).release(session.lease());
    }

    @Test void occupiedFailedCopyDoesNotBlockNewEventsAndForceCancelReleasesIt() throws Exception {
        install(ComboRun.State.RUNNING);
        ComboSession session = session(0); field("session", session);
        Player player = player(1);
        when(returns.pending(player.getUniqueId())).thenReturn(true);
        when(returns.restore(player)).thenReturn(false);
        when(session.contains(player.getLocation())).thenReturn(true);
        var tournament = mock(TournamentManager.class);
        when(tournament.playing()).thenReturn(Map.of());
        when(server.plugin.getTournamentManager()).thenReturn(tournament);
        var modes = new MinigameManager(server.plugin); modes.register(manager);

        manager.end(ComboRun.State.INTERRUPTED);

        assertFalse(manager.busy());
        assertDoesNotThrow(() -> modes.beforeCreate("mace_race"));
        verify(arenas).discard(session.lease());
        verify(arenas, never()).release(session.lease());
        manager.forceCancel();
        verify(arenas).abandon(session.lease());
        assertTrue(manager.pendingReturn(player.getUniqueId()));
        verify(player, never()).teleport(any(Location.class));
    }

    @Test void oldResetCompletionCannotClearANewEventsResetOrReleaseTheAbandonedLease() throws Exception {
        install(ComboRun.State.RUNNING);
        ComboSession first = session(0); field("session", first);
        var firstReset = new CompletableFuture<Void>(); when(arenas.reset(first.lease())).thenReturn(firstReset);
        manager.end(ComboRun.State.INTERRUPTED); assertTrue(manager.busy());
        manager.forceCancel(); assertFalse(manager.busy());

        install(ComboRun.State.RUNNING);
        ComboSession second = session(1); field("session", second);
        var secondReset = new CompletableFuture<Void>(); when(arenas.reset(second.lease())).thenReturn(secondReset);
        manager.end(ComboRun.State.FINISHED); assertTrue(manager.busy());
        firstReset.completeExceptionally(new IllegalStateException("Old reset failed"));

        assertTrue(manager.busy());
        verify(arenas, never()).release(first.lease());
        verify(arenas).abandon(first.lease());
        secondReset.complete(null);
        assertFalse(manager.busy()); verify(arenas).release(second.lease());
    }

    @Test void forceCancelRetriesEveryOnlineReturnAndKeepsFailedAndOfflineSnapshots() throws Exception {
        install(ComboRun.State.INTERRUPTED);
        Player blocked = player(1), restored = player(2);
        UUID offline = UUID.randomUUID();
        when(returns.pending(blocked.getUniqueId())).thenReturn(true);
        when(returns.pending(restored.getUniqueId())).thenReturn(true);
        when(returns.pending(offline)).thenReturn(true);
        when(returns.restore(blocked)).thenThrow(new IllegalStateException("Inventory unavailable"));
        when(returns.restore(restored)).thenAnswer(call -> {
            when(returns.pending(restored.getUniqueId())).thenReturn(false); return true;
        });

        assertDoesNotThrow(manager::forceCancel);

        verify(returns).restore(blocked); verify(returns).restore(restored);
        assertTrue(manager.pendingReturn(blocked.getUniqueId())); assertTrue(manager.pendingReturn(offline));
        assertFalse(manager.pendingReturn(restored.getUniqueId())); assertFalse(manager.busy());
    }

    @Test void cleanupFailureStillQuarantinesTheLeaseAndRecoversOtherPlayers() throws Exception {
        install(ComboRun.State.RUNNING);
        ComboSession session = session(0); field("session", session);
        doThrow(new IllegalStateException("Bot removal failed")).when(session).disable();
        Player player = player(1);
        when(returns.pending(player.getUniqueId())).thenReturn(true);
        when(returns.restore(player)).thenReturn(false);

        assertDoesNotThrow(manager::forceCancel);

        verify(arenas).abandon(session.lease()); verify(returns).restore(player);
        assertFalse(manager.active()); assertFalse(manager.busy()); assertTrue(manager.pendingReturn(player.getUniqueId()));
    }

    @Test void forceCancelReturnsAnUnregisteredObserverWithoutChangingTheirInventory() throws Exception {
        install(ComboRun.State.INTERRUPTED);
        ComboSession session = session(0); field("session", session);
        Player observer = player(1), outsider = player(1000);
        when(observer.teleport(lobby)).thenReturn(true);

        manager.forceCancel();

        verify(observer).teleport(lobby);
        verify(observer, never()).getInventory();
        verify(returns, never()).restore(observer);
        verify(outsider, never()).teleport(any(Location.class));
    }

    @Test void synchronousResetFailureQuarantinesCopyWithoutLeavingCleanupBusy() throws Exception {
        install(ComboRun.State.RUNNING);
        ComboSession session = session(0); field("session", session);
        when(arenas.reset(session.lease())).thenThrow(new IllegalStateException("No template"));

        assertDoesNotThrow(() -> manager.end(ComboRun.State.INTERRUPTED));

        verify(arenas).abandon(session.lease()); assertFalse(manager.busy());
    }

    private ComboRun install(ComboRun.State state) throws Exception {
        ComboRun run = ComboRunTest.run(1, 5);
        if (state == ComboRun.State.RUNNING) run.start();
        else if (state != ComboRun.State.READY) run.end(state);
        field("current", run); return run;
    }
    private ComboSession session(int slot) {
        var map = new ArenaMap("platform"); map.setWorldName("arenas"); map.setRollbackRegion(0, 60, 0, 10, 70, 10);
        var lease = new ArenaLease(new ArenaInstance(slot, map, new BlockBounds(-100, -64, -100, 100, 320, 100)), UUID.randomUUID());
        ComboSession session = mock(ComboSession.class); when(session.lease()).thenReturn(lease); return session;
    }
    private Player player(int x) {
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true); when(player.getLocation()).thenReturn(new Location(world, x, 65, 1));
        online.add(player); return player;
    }
    private void field(String name, Object value) throws Exception {
        Field field = ComboManager.class.getDeclaredField(name); field.setAccessible(true); field.set(manager, value);
    }
}
