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
    @Test void failedPasteCanRetryWithTheSameLeaseAndProtectEveryoneAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), visitor = f.player(900, 200, 900);
            when(f.world.getPlayers()).thenReturn(List.of(contestant, visitor));
            List<Player> protectedPlayers = new ArrayList<>();
            Set<UUID> participants = Set.of(contestant.getUniqueId());
            var first = f.manager.resetRound(f.lease, participants, protectedPlayers::add);
            f.runPasteBoundary(new IllegalStateException("Temporary paste failure"));

            assertThrows(CompletionException.class, first::join);
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
            assertTrue(f.instance.owns(f.lease));
            assertNull(f.manager.acquire("arena"));
            assertThrows(CompletionException.class, () -> f.manager.reset(f.lease).join());
            assertThrows(CompletionException.class, () -> f.manager.resetRound(f.lease, participants).join());

            var retry = f.manager.resetRound(f.lease, participants, protectedPlayers::add);
            assertEquals(ArenaInstance.Status.RESETTING, f.instance.status());
            f.runPasteBoundary();

            assertDoesNotThrow(retry::join);
            assertEquals(List.of(contestant, visitor, contestant, visitor), protectedPlayers);
            assertEquals(2, f.pastes.size());
            assertEquals(ArenaInstance.Status.IN_USE, f.instance.status());
            assertTrue(f.instance.owns(f.lease));
        }
    }

    @Test void failedCopyCannotRetryWhileAnEarlierPasteIsStillRunning() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(contestant));
            List<Player> protectedPlayers = new ArrayList<>();
            Set<UUID> participants = Set.of(contestant.getUniqueId());
            var first = f.manager.resetRound(f.lease, participants, protectedPlayers::add);
            assertThrows(ChecksPassed.class, () -> f.pastes.getFirst().beforePaste().get());
            f.manager.discard(f.lease);

            var blocked = f.manager.resetRound(f.lease, participants, protectedPlayers::add);

            assertThrows(CompletionException.class, blocked::join);
            assertFalse(first.isDone());
            assertEquals(1, f.pastes.size());
            assertEquals(List.of(contestant), protectedPlayers);
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
            f.pastes.getFirst().completion().completeExceptionally(new IllegalStateException("Previous paste finished"));
            assertThrows(CompletionException.class, first::join);

            var retry = f.manager.resetRound(f.lease, participants, protectedPlayers::add);
            f.runPasteBoundary();
            assertDoesNotThrow(retry::join);
            assertEquals(2, f.pastes.size());
        }
    }

    @Test void releasedFailedCopyCannotBeRetriedWithItsExpiredLease() throws Exception {
        try (Fixture f = new Fixture()) {
            var first = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()), player -> { });
            f.runPasteBoundary(new IllegalStateException("Temporary paste failure"));
            assertThrows(CompletionException.class, first::join);
            f.manager.release(f.lease);

            var retry = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()), player -> fail("Expired owner"));

            assertThrows(CompletionException.class, retry::join);
            assertEquals(1, f.pastes.size());
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
            assertFalse(f.instance.isReserved());
        }
    }

    @Test void maintenanceCannotRepairOrReassignAFailedReservedRound() throws Exception {
        try (Fixture f = new Fixture()) {
            var reset = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()), player -> { });
            f.runPasteBoundary(new IllegalStateException("Temporary paste failure"));
            assertThrows(CompletionException.class, reset::join);

            f.manager.maintain();
            f.manager.maintain();

            assertEquals(1, f.pastes.size());
            assertNull(f.manager.acquire("arena"));
            assertTrue(f.instance.owns(f.lease));
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
        }
    }

    @Test void failedReservationRecoveryNeverRevivesAnOlderResetRevision() {
        ArenaInstance instance = new ArenaInstance(0, new ArenaMap("arena"), new BlockBounds(0, 0, 0, 15, 15, 15));
        instance.reuseSavedCopy();
        ArenaLease lease = instance.acquire();
        assertThrows(IllegalStateException.class, () -> instance.retryReset(lease));
        long failed = instance.beginReset();
        instance.failed(failed);
        assertThrows(IllegalStateException.class, () -> instance.retryReset(new ArenaLease(instance, UUID.randomUUID())));

        instance.retryReset(lease);
        long retried = instance.beginReset();

        assertFalse(instance.restored(failed));
        instance.failed(failed);
        assertTrue(instance.isReset(retried));
        assertFalse(instance.canCache());
        assertTrue(instance.restored(retried));
        assertTrue(instance.owns(lease));
        assertFalse(instance.canCache(), "The recovered copy remains reserved");
    }

    @Test void registeredPlayersAndVisitorsAcrossTheWholeCellAreProtectedBeforePasting() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), spectator = f.player(10, 75, 10);
            Player director = f.player(900, 500, 900), elsewhere = f.player(1024, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(contestant, spectator, director, elsewhere));
            List<Player> protectedPlayers = new ArrayList<>();
            Thread mainThread = Thread.currentThread();

            var reset = f.manager.resetRound(f.lease,
                    Set.of(contestant.getUniqueId(), spectator.getUniqueId()), player -> {
                        assertSame(mainThread, Thread.currentThread());
                        verify(f.world, never()).getEntities();
                        protectedPlayers.add(player);
                    });

            assertTrue(protectedPlayers.isEmpty(), "Protection runs after chunk loading, just before the paste");
            f.runPasteBoundary();

            assertDoesNotThrow(reset::join);
            assertEquals(List.of(contestant, spectator, director), protectedPlayers);
            assertEquals(ArenaInstance.Status.IN_USE, f.instance.status());
            verify(f.world).getEntities();
        }
    }

    @Test void visitorsWhoArriveDuringChunkLoadingAreProtectedByThePasteBoundary() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), lateVisitor = f.player(10, 500, 10);
            when(f.world.getPlayers()).thenReturn(List.of(contestant));
            List<Player> protectedPlayers = new ArrayList<>();
            var reset = f.manager.resetRound(f.lease, Set.of(contestant.getUniqueId()), protectedPlayers::add);

            when(f.world.getPlayers()).thenReturn(List.of(contestant, lateVisitor));
            f.runPasteBoundary();

            assertDoesNotThrow(reset::join);
            assertEquals(List.of(contestant, lateVisitor), protectedPlayers);
        }
    }

    @Test void failedOccupantProtectionPreventsEntityRemovalAndPasting() throws Exception {
        try (Fixture f = new Fixture()) {
            Player contestant = f.player(5, 65, 5), visitor = f.player(900, 200, 900);
            when(f.world.getPlayers()).thenReturn(List.of(contestant, visitor));
            List<Player> protectedPlayers = new ArrayList<>();
            var reset = f.manager.resetRound(f.lease, Set.of(contestant.getUniqueId()), player -> {
                if (player == visitor) throw new IllegalStateException("Protection failed");
                protectedPlayers.add(player);
            });

            f.runPasteBoundary();

            var failure = assertThrows(CompletionException.class, reset::join);
            assertEquals("Protection failed", failure.getCause().getMessage());
            assertEquals(List.of(contestant), protectedPlayers);
            verify(f.world, never()).getEntities();
            assertEquals(ArenaInstance.Status.FAILED, f.instance.status());
        }
    }

    @Test void expiredLeasePreventsCallingTheOccupantProtector() throws Exception {
        try (Fixture f = new Fixture()) {
            Player visitor = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(visitor));
            List<Player> protectedPlayers = new ArrayList<>();
            var reset = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()), protectedPlayers::add);
            f.manager.release(f.lease);

            f.runPasteBoundary();

            assertThrows(CompletionException.class, reset::join);
            assertTrue(protectedPlayers.isEmpty());
            verify(f.world, never()).getEntities();
        }
    }

    @Test void protectionThatExpiresTheLeaseCannotProceedToThePaste() throws Exception {
        try (Fixture f = new Fixture()) {
            Player visitor = f.player(5, 65, 5);
            when(f.world.getPlayers()).thenReturn(List.of(visitor));
            var reset = f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()), player -> f.manager.release(f.lease));

            f.runPasteBoundary();

            assertThrows(CompletionException.class, reset::join);
            verify(f.world, never()).getEntities();
        }
    }

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
            assertThrows(CompletionException.class, () -> f.manager.resetRound(f.lease, Set.of(UUID.randomUUID()), null).join());
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
        final MapManager maps = mock(MapManager.class);
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
            manager = new ArenaManager(server.plugin, maps);
            field(manager, "world", world);
            ArenaMap map = new ArenaMap("arena");
            map.setWorldName("arenas");
            map.setRollbackRegion(0, 60, 0, 15, 80, 15);
            map.setTeam1Spawn(2, 65, 2, 0, 0);
            map.setTeam2Spawn(12, 65, 12, 0, 0);
            when(maps.getMaps()).thenReturn(List.of(map));
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
            runPasteBoundary(null);
        }

        void runPasteBoundary(RuntimeException pasteFailure) {
            Paste paste = pastes.getLast();
            try {
                paste.beforePaste().get();
                fail("Expected the mocked FAWE boundary");
            } catch (ChecksPassed checked) {
                if (pasteFailure == null) paste.completion().complete(null);
                else paste.completion().completeExceptionally(pasteFailure);
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
