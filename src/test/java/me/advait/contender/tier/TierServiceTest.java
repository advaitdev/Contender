package me.advait.contender.tier;

import me.advait.contender.testutil.StateTestServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TierServiceTest {
    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> jobs = new ArrayDeque<>();
        boolean stopped;
        @Override public void execute(Runnable action) { jobs.add(action); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; var left = List.copyOf(jobs); jobs.clear(); return left; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
    @Test void coalescesRequestsPublishesOnMainAndKeepsLastTierDuringFailures() {
        try (var server = new StateTestServer()) {
            when(server.plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            UUID uuid = UUID.randomUUID();
            PlayerData data = new PlayerData(uuid.toString(), "Alice", "NA", Map.of());
            AtomicLong clock = new AtomicLong();
            AtomicInteger calls = new AtomicInteger();
            ManualExecutor worker = new ManualExecutor();
            try (TierService service = new TierService(server.plugin, key -> {
                if (calls.incrementAndGet() > 1) throw new IOException("Unavailable");
                return data;
            }, clock::get, worker)) {
                var first = service.refresh(uuid);
                var second = service.refresh(uuid);
                assertSame(first, second);
                assertEquals(1, worker.jobs.size());
                worker.jobs.remove().run();
                assertFalse(first.isDone(), "Completion waits for the server thread");
                server.scheduled.getLast().run();
                assertSame(data, first.join());
                assertSame(data, service.byName("Alice").join());
                assertTrue(worker.jobs.isEmpty());
                clock.set(300_001);
                var retry = service.refresh(uuid);
                worker.jobs.remove().run();
                server.scheduled.getLast().run();
                assertThrows(CompletionException.class, retry::join);
                assertSame(data, service.cached(uuid));
                assertTrue(service.refresh(uuid).isCompletedExceptionally());
                assertTrue(worker.jobs.isEmpty(), "Failures are backed off for a minute");
            }
        }
    }
    @Test void cachesMissingProfilesAndCancelsCallbacksOnShutdown() {
        try (var server = new StateTestServer()) {
            ManualExecutor worker = new ManualExecutor();
            TierService service = new TierService(server.plugin, key -> null, () -> 0, worker);
            var missing = service.byName("Unknown");
            worker.jobs.remove().run();
            server.scheduled.getLast().run();
            assertNull(missing.join());
            assertNull(service.byName("unknown").join());
            assertTrue(worker.jobs.isEmpty());
            var late = service.refresh(UUID.randomUUID());
            worker.jobs.remove().run();
            service.close();
            server.scheduled.getLast().run();
            assertTrue(late.isCancelled());
            assertTrue(worker.isShutdown());
        }
    }
}
