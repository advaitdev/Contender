package me.advait.contender.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import me.advait.contender.map.MapManager;
import me.advait.contender.testutil.StateTestServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArenaRoundResetTest {
    @Test void contestantsAndSpectatorsCanRemainInsideAnExplicitRoundReset() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), spectator = f.player(10, 75, 10);
            when(f.world.getPlayers()).thenReturn(List.of(contestant, spectator));
            Set<UUID> participants = Set.of(contestant.getUniqueId(), spectator.getUniqueId());

            var reset = f.manager.resetRound(f.lease, participants);

            assertSame(reset, f.manager.resetRound(f.lease, participants));
            assertFalse(reset.isDone());
            assertEquals(ArenaInstance.Status.RESETTING, f.instance.status());
            assertTrue(f.manager.isPreparingEntry(new Location(mock(World.class), 0, 65, 0),
                    new Location(f.world, 5, 65, 5)), "Outsiders cannot enter during the occupied reset");
            f.runPasteBoundary();
            assertDoesNotThrow(reset::join);
            assertEquals(ArenaInstance.Status.IN_USE, f.instance.status());
            assertTrue(f.instance.owns(f.lease));
            verify(f.world).getEntities();
        }
    }

    @Test void unrelatedOccupantsStillPreventRoundResetsAcrossTheWholeCell() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), observer = f.player(900, 200, 900);
            when(f.world.getPlayers()).thenReturn(List.of(contestant, observer));

            var reset = f.manager.resetRound(f.lease, Set.of(contestant.getUniqueId()));
            f.runPasteBoundary();

            var failure = assertThrows(CompletionException.class, reset::join);
            assertTrue(failure.getCause().getMessage().contains("Move other players out"));
            verify(f.world, never()).getEntities();
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
        }
    }

    @Test void normalResetStillRequiresEveryoneToLeave() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(contestant));

            var reset = f.manager.reset(f.lease);
            f.runPasteBoundary();

            var failure = assertThrows(CompletionException.class, reset::join);
            assertTrue(failure.getCause().getMessage().contains("Move everyone out"));
            verify(f.world, never()).getEntities();
        }
    }

    @Test void callerCannotRemoveOrAddPermissionsAfterQueuingTheReset() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(contestant));
            Set<UUID> mutable = new HashSet<>(Set.of(contestant.getUniqueId()));
            var reset = f.manager.resetRound(f.lease, mutable);
            mutable.clear();

            f.runPasteBoundary();

            assertDoesNotThrow(reset::join);
        }
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), observer = f.player(10, 70, 10);
            when(f.world.getPlayers()).thenReturn(List.of(contestant, observer));
            Set<UUID> mutable = new HashSet<>(Set.of(contestant.getUniqueId()));
            var reset = f.manager.resetRound(f.lease, mutable);
            mutable.add(observer.getUniqueId());

            f.runPasteBoundary();

            assertThrows(CompletionException.class, reset::join);
            verify(f.world, never()).getEntities();
        }
    }

    @Test void anExpiredLeaseCannotStartQueuedChunkWork() throws Exception {
        try (Fixture f = new Fixture()) {
            CompletableFuture<Void> previous = new CompletableFuture<>();
            field(f.manager, "work", previous);
            var reset = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()));
            f.manager.release(f.lease);

            previous.complete(null);

            assertThrows(CompletionException.class, reset::join);
            assertTrue(f.pastes.isEmpty());
            verify(f.world, never()).getEntities();
        }
    }

    @Test void leaseOwnershipIsCheckedAgainAfterChunksLoad() throws Exception {
        try (Fixture f = new Fixture()) {
            var reset = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()));
            f.manager.release(f.lease);

            f.runPasteBoundary();

            assertThrows(CompletionException.class, reset::join);
            verify(f.world, never()).getEntities();
        }
    }

    @Test void aNormalResetCannotBorrowAnOccupiedResetsAllowance() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(contestant));
            var round = f.manager.resetRound(f.lease, Set.of(contestant.getUniqueId()));
            var cleanup = f.manager.reset(f.lease);

            f.runPasteBoundary();

            assertDoesNotThrow(round::join);
            assertThrows(CompletionException.class, cleanup::join);
            assertEquals(1, f.pastes.size(), "Validation must not schedule a redundant paste");
        }
    }

    @Test void cleanupCanJoinTheExistingPasteAfterEveryoneLeaves() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(contestant));
            var round = f.manager.resetRound(f.lease, Set.of(contestant.getUniqueId()));
            var cleanup = f.manager.reset(f.lease);
            when(f.world.getPlayers()).thenReturn(List.of());

            f.runPasteBoundary();

            assertDoesNotThrow(round::join);
            assertDoesNotThrow(cleanup::join);
            assertEquals(1, f.pastes.size());
            verify(f.world, times(1)).getEntities();
        }
    }

    @Test void emptyOrInvalidAllowancesAndStaleReservationsAreRejectedBeforeWork() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(CompletionException.class, () -> f.manager.resetRound(f.lease, Set.of()).join());
            assertThrows(CompletionException.class, () -> f.manager.resetRound(f.lease, null).join());
            Set<UUID> invalid = new HashSet<>(); invalid.add(null);
            assertThrows(CompletionException.class, () -> f.manager.resetRound(f.lease, invalid).join());
            f.manager.release(f.lease);
            assertThrows(CompletionException.class, () -> f.manager.resetRound(f.lease, Set.of(UUID.randomUUID())).join());
            assertThrows(CompletionException.class, () -> f.manager.resetRound(null, Set.of(UUID.randomUUID())).join());
            assertTrue(f.pastes.isEmpty());
        }
    }

    private record Paste(Supplier<CompletableFuture<Void>> beforePaste, CompletableFuture<Void> completion) { }
    /** The FAWE boundary is mocked; reaching entity cleanup proves the main-thread checks passed. */
    private static final class ChecksPassed extends RuntimeException { }

    private static final class Fixture implements AutoCloseable {
        final StateTestServer server = new StateTestServer();
        final World world = mock(World.class);
        final List<Paste> pastes = new ArrayList<>();
        final MockedConstruction<ArenaChunks> chunks;
        final ArenaManager manager;
        final ArenaInstance instance;
        final ArenaLease lease;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            chunks = mockConstruction(ArenaChunks.class, (loading, context) -> when(loading.run(any(), any(), any())).thenAnswer(call -> {
                CompletableFuture<Void> completion = new CompletableFuture<>();
                pastes.add(new Paste(call.getArgument(2), completion));
                return completion;
            }));
            manager = new ArenaManager(server.plugin, mock(MapManager.class));
            field(manager, "world", world);
            ArenaMap map = new ArenaMap("arena");
            map.setWorldName("arenas");
            map.setRollbackRegion(0, 60, 0, 15, 80, 15);
            instance = new ArenaInstance(0, map, new BlockBounds(0, -64, 0, 1023, 319, 1023));
            instance.reuseSavedCopy();
            lease = instance.acquire();
            ((Map<String, List<ArenaInstance>>) field(manager, "pools")).put("arena", List.of(instance));
            ((Map<String, Clipboard>) field(manager, "clipboards")).put("arena", mock(Clipboard.class));
            when(world.getEntities()).thenThrow(new ChecksPassed());
        }

        Player player(double x, double y, double z) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getLocation()).thenReturn(new Location(world, x, y, z));
            return player;
        }

        void runPasteBoundary() {
            Paste paste = pastes.getLast();
            try {
                paste.beforePaste().get();
                fail("Expected the mocked FAWE boundary");
            } catch (ChecksPassed checked) {
                paste.completion().complete(null);
            } catch (RuntimeException failure) {
                paste.completion().completeExceptionally(failure);
            }
        }

        @Override public void close() {
            manager.shutdown();
            chunks.close();
            server.close();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
