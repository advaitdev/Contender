package me.advait.contender.race;

import me.advait.contender.Contender;
import me.advait.contender.arena.*;
import me.advait.contender.lobby.LobbyManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.minigame.RaceMode;
import me.advait.contender.role.RoleManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RaceForceCancelTest {
    @TempDir Path folder;

    @Test void interruptedRaceDetachesItsSessionAndDiscardsEveryLease() throws Exception {
        try (var f = new Fixture()) {
            RaceSession active = f.session(1), stranded = f.session(2);
            f.run.end(RaceRun.State.INTERRUPTED);
            set(f.manager, "session", active);
            set(f.manager, "resettingLease", f.lease(3));
            stranded(f.manager).put(stranded.lease(), stranded);
            f.manager.forceCancel();
            assertFalse(f.manager.busy()); assertFalse(f.manager.active()); assertNull(f.manager.displayed());
            assertEquals(RaceRun.State.CANCELLED, f.run.state());
            verify(active).disable(); verify(f.arenas).abandon(active.lease()); verify(f.arenas).abandon(stranded.lease());
            verify(f.arenas, times(3)).abandon(any());
            var saved = new RaceStore(folder.toFile()).load();
            assertFalse(saved.selected()); assertEquals(RaceRun.State.CANCELLED, saved.run().state());
            f.manager.forceCancel();
            verify(active, times(1)).disable(); verify(f.arenas, times(3)).abandon(any());
        }
    }

    @Test void anOldResetCannotClearTheNewRacesResetLockOrReleaseItsLease() throws Exception {
        try (var f = new Fixture()) {
            RaceSession first = f.session(1), next = f.session(2);
            var oldReset = new CompletableFuture<Void>(); var newReset = new CompletableFuture<Void>();
            when(f.arenas.reset(first.lease())).thenReturn(oldReset);
            when(f.arenas.reset(next.lease())).thenReturn(newReset);
            set(f.manager, "session", first);
            f.manager.end(RaceRun.State.INTERRUPTED); assertTrue(f.manager.busy());
            f.manager.forceCancel(); assertFalse(f.manager.busy());
            set(f.manager, "current", f.run()); set(f.manager, "session", next);
            f.manager.end(RaceRun.State.CANCELLED); assertTrue(f.manager.busy());
            oldReset.complete(null); assertTrue(f.manager.busy());
            verify(f.arenas, never()).release(first.lease()); verify(f.arenas, never()).release(next.lease());
            newReset.complete(null); assertFalse(f.manager.busy()); verify(f.arenas).release(next.lease());
        }
    }

    @Test void normalEndStillCleansATerminalSessionAndStrandedCopiesDoNotBlockOtherGames() throws Exception {
        try (var f = new Fixture()) {
            RaceSession session = f.session(1);
            Player observer = f.player("Observer"); when(session.contains(any())).thenReturn(true);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(observer));
            f.run.end(RaceRun.State.INTERRUPTED); set(f.manager, "session", session);
            f.manager.end(RaceRun.State.CANCELLED);
            verify(session).disable(); verify(f.arenas).discard(session.lease());
            assertFalse(f.manager.busy()); assertEquals(1, stranded(f.manager).size());
            f.manager.forceCancel(); verify(observer).teleport(f.lobby); verify(f.arenas).abandon(session.lease());
            verify(observer.getInventory(), never()).setContents(any());
        }
    }

    @Test void forceCancelRestoresOnlineSnapshotsButKeepsBlockedAndOfflinePlayersRecoverable() throws Exception {
        try (var f = new Fixture()) {
            Player restored = f.player("Restored"), blocked = f.player("Blocked"), offline = f.player("Offline");
            when(restored.teleport(f.lobby)).thenReturn(true);
            f.manager.capture(restored); f.manager.capture(blocked); f.manager.capture(offline);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(restored, blocked));
            f.run.end(RaceRun.State.INTERRUPTED);
            f.manager.forceCancel();
            assertFalse(f.manager.pendingReturn(restored.getUniqueId()));
            assertTrue(f.manager.pendingReturn(blocked.getUniqueId())); assertTrue(f.manager.pendingReturn(offline.getUniqueId()));
            verify(restored.getInventory()).setContents(any()); verify(blocked.getInventory(), never()).setContents(any());
            assertTrue(new RaceReturns(f.plugin).pending(blocked.getUniqueId()));
        }
    }

    @Test void forceCancelStillReleasesLeaseAfterSessionCleanupThrows() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session(1); set(f.manager, "session", session);
            doThrow(new IllegalStateException("snapshot failed")).when(session).disable();
            assertThrows(IllegalStateException.class, f.manager::forceCancel);
            assertFalse(f.manager.busy()); verify(f.arenas).abandon(session.lease());
        }
    }

    @Test void modeDelegatesEmergencyCancellation() {
        var plugin = mock(Contender.class); var manager = mock(RaceManager.class);
        when(plugin.getRaceManager()).thenReturn(manager);
        new RaceMode(plugin).forceCancel(); verify(manager).forceCancel();
    }

    private final class Fixture implements AutoCloseable {
        final Contender plugin = mock(Contender.class);
        final ArenaManager arenas = mock(ArenaManager.class);
        final World world = mock(World.class);
        final Location lobby = new Location(mock(World.class), 0, 70, 0);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final RaceRun run = run();
        final RaceManager manager;
        Fixture() throws Exception {
            when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(plugin.getArenaManager()).thenReturn(arenas); when(plugin.getRoleManager()).thenReturn(mock(RoleManager.class));
            var lobbies = mock(LobbyManager.class); when(plugin.getLobbyManager()).thenReturn(lobbies); when(lobbies.getLobbyLocation()).thenReturn(lobby);
            when(world.getName()).thenReturn("race_copies");
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            manager = new RaceManager(plugin); set(manager, "current", run); set(manager, "selected", true);
        }
        RaceRun run() { return new RaceRun(UUID.randomUUID(), "Mace Race", "race", 15, 600, List.of(new RaceRun.Racer(UUID.randomUUID(), "Racer"))); }
        ArenaLease lease(int slot) {
            var map = mock(ArenaMap.class); when(map.getWorldName()).thenReturn("race_copies");
            return new ArenaLease(new ArenaInstance(slot, map, new BlockBounds(-10, 0, -10, 10, 200, 10)), UUID.randomUUID());
        }
        RaceSession session(int slot) { var result = mock(RaceSession.class); var lease = lease(slot); when(result.lease()).thenReturn(lease); return result; }
        Player player(String name) {
            var player = mock(Player.class); var inventory = mock(PlayerInventory.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn(name);
            when(player.getInventory()).thenReturn(inventory); when(inventory.getContents()).thenReturn(new ItemStack[41]);
            when(player.getGameMode()).thenReturn(GameMode.CREATIVE); when(player.getHealth()).thenReturn(20d); when(player.getMaxHealth()).thenReturn(20d);
            when(player.getActivePotionEffects()).thenReturn(List.of()); when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
            return player;
        }
        public void close() { bukkit.close(); }
    }
    @SuppressWarnings("unchecked")
    private static Map<ArenaLease, RaceSession> stranded(RaceManager manager) throws Exception { return (Map<ArenaLease, RaceSession>) field(manager, "stranded").get(manager); }
    private static Field field(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
}
