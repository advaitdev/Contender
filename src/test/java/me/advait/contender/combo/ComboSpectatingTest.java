package me.advait.contender.combo;

import me.advait.contender.Contender;
import me.advait.contender.arena.ArenaInstance;
import me.advait.contender.arena.ArenaLease;
import me.advait.contender.game.AbstractGameState;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComboSpectatingTest {
    private Contender plugin;
    private ComboManager manager;
    private ComboRun run;
    private ComboSession session;
    private World world;
    private Location spectatorSpawn;
    private MockedStatic<Bukkit> bukkit;
    private final List<Player> players = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        plugin = mock(Contender.class);
        manager = mock(ComboManager.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("combo_arena");
        when(world.getEntities()).thenReturn(List.of());
        run = ComboRunTest.run(4, 5);
        run.start();
        var map = new ArenaMap("combo");
        map.setWorldName("combo_arena");
        map.setRollbackRegion(0, 60, 0, 20, 80, 20);
        map.setTeam1Spawn(2, 65, 2, 0, 0);
        map.setTeam2Spawn(5, 65, 2, 0, 0);
        map.setSpectatorSpawn(10, 75, 10, 90, 15);
        spectatorSpawn = new Location(world, 10, 75, 10, 90, 15);
        session = new ComboSession(plugin, manager, run, new ArenaLease(
                new ArenaInstance(0, map, new BlockBounds(-100, -64, -100, 100, 320, 100)), UUID.randomUUID()));
        field(AbstractGameState.class, session, "enabled", true);
        // Keep this fixture between turns so it can exercise enrollment without spawning a mannequin.
        field(ComboSession.class, session, "nextTurn", Integer.MAX_VALUE);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("combo_arena")).thenReturn(world);
        for (var entry : run.entries()) {
            Player player = player(entry.id(), entry.name());
            players.add(player);
            bukkit.when(() -> Bukkit.getPlayer(entry.id())).thenReturn(player);
            when(manager.available(entry)).thenReturn(true);
            when(manager.restore(player)).thenReturn(true);
        }
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
    }

    @AfterEach void close() { bukkit.close(); }

    @Test void allWaitingContestantsAreBroughtToTheArenaAsSpectatorsOnTheFirstTick() throws Exception {
        tick();

        for (Player player : players) {
            verify(manager).capture(player);
            verify(player).teleport(spectatorSpawn);
            verify(player).setGameMode(GameMode.SPECTATOR);
            assertTrue(session.owns(player.getUniqueId()));
            assertTrue(session.watching(player.getUniqueId()));
            assertFalse(session.playing(player.getUniqueId()));
        }
        assertNull(run.playing());
    }

    @Test void completedContestantsWatchButWithdrawnUnavailableAndUnrelatedPlayersAreLeftAlone() throws Exception {
        ComboRun.Entry completed = run.entries().get(0);
        run.begin(completed.id());
        for (int i = 0; i < 6; i++) run.hit(completed.id());
        assertEquals(ComboRun.Result.SCORED, run.hitBack());
        run.withdraw(run.entries().get(1).id());
        when(manager.available(run.entries().get(2))).thenReturn(false);
        Player outsider = player(UUID.randomUUID(), "OutsideTheRoster");
        players.add(outsider);

        tick();

        verify(manager).capture(players.get(0));
        verify(manager).capture(players.get(3));
        assertTrue(session.watching(completed.id()));
        for (Player untouched : List.of(players.get(1), players.get(2), outsider)) {
            verify(manager, never()).capture(untouched);
            verify(untouched, never()).teleport(any(Location.class));
            verify(untouched, never()).setGameMode(any(GameMode.class));
            assertFalse(session.owns(untouched.getUniqueId()));
        }
    }

    @Test void periodicChecksPreserveTheOriginalSnapshotAndCleanupReturnsEveryWatcher() throws Exception {
        tick();
        enrollmentTick();
        enrollmentTick();

        for (Player player : players) {
            verify(manager).capture(player);
            verify(player).teleport(spectatorSpawn);
            verify(manager, never()).restore(player);
        }

        session.disable();

        for (Player player : players) {
            verify(manager).restore(player);
            assertFalse(session.owns(player.getUniqueId()));
            assertFalse(session.watching(player.getUniqueId()));
        }
    }

    @Test void leavingForTheLobbyDoesNotImmediatelyPullThePlayerBackOrRemoveTheirTurn() throws Exception {
        tick();
        Player player = players.getFirst();

        assertTrue(session.unwatch(player));
        enrollmentTick();
        enrollmentTick();

        verify(manager).restore(player);
        verify(manager).capture(player);
        verify(player).teleport(spectatorSpawn);
        assertFalse(session.owns(player.getUniqueId()));
        assertFalse(run.entry(player.getUniqueId()).done());
    }

    @Test void withdrawalAlsoLeavesCompletedPlayersOutOfAutomaticSpectating() throws Exception {
        var entry = run.entries().getFirst();
        run.begin(entry.id());
        for (int i = 0; i < 6; i++) run.hit(entry.id());
        run.hitBack();
        tick();
        Player player = players.getFirst();

        session.withdraw(entry.id());
        enrollmentTick();

        verify(manager).restore(player);
        verify(manager).capture(player);
        assertFalse(session.owns(entry.id()));
        assertEquals(6, entry.score(), "Leaving after a finished turn must keep the result");
    }

    @Test void reconnectingWatchersRejoinOnlyAfterTheirPreviousReturnHasBeenRecovered() throws Exception {
        tick();
        var entry = run.entries().getFirst();
        Player original = players.getFirst();
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(original);
        session.quit(quit);
        assertFalse(session.owns(entry.id()));
        assertFalse(session.watching(entry.id()));
        Player reconnected = player(entry.id(), entry.name());
        bukkit.when(() -> Bukkit.getPlayer(entry.id())).thenReturn(reconnected);
        players.set(0, reconnected);
        // ComboManager keeps a reconnect unavailable until the old return snapshot is restored.
        when(manager.available(entry)).thenReturn(false);

        enrollmentTick();
        verify(manager, never()).capture(reconnected);
        assertFalse(session.owns(entry.id()));

        when(manager.available(entry)).thenReturn(true);
        enrollmentTick();

        verify(manager).capture(reconnected);
        verify(reconnected).teleport(spectatorSpawn);
        verify(reconnected).setGameMode(GameMode.SPECTATOR);
        assertTrue(session.watching(entry.id()));
        assertFalse(entry.done());
    }

    @Test void blockedAutomaticSpectatorTeleportRestoresThePlayerAndReleasesOwnership() throws Exception {
        Player player = players.getFirst();
        when(player.teleport(any(Location.class))).thenReturn(false);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, this::tick);

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        verify(manager).capture(player);
        verify(manager).restore(player);
        assertFalse(session.owns(player.getUniqueId()));
        assertFalse(session.watching(player.getUniqueId()));
    }

    private Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.teleport(any(Location.class))).thenReturn(true);
        return player;
    }

    private void enrollmentTick() throws Exception {
        field(ComboSession.class, session, "tick", 19);
        tick();
    }

    private void tick() throws Exception {
        Method method = ComboSession.class.getDeclaredMethod("tick");
        method.setAccessible(true);
        method.invoke(session);
    }

    private static void field(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
