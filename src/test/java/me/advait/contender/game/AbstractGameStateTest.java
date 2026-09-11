package me.advait.contender.game;

import me.advait.contender.testutil.StateTestServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class AbstractGameStateTest {
    @Test
    void disableUnregistersOnlyItsOwnListenerAndCancelsAllTasks() {
        try (var server = new StateTestServer()) {
            AtomicInteger calls = new AtomicInteger();
            class TimedState extends AbstractGameState {
                TimedState() { super(server.plugin); }
                @Override protected void onEnable() {
                    runLater(calls::incrementAndGet, 1L);
                    runRepeating(calls::incrementAndGet, 0L, 20L);
                }
            }
            var first = new TimedState();
            var second = new TimedState();
            first.enable();
            first.enable(); // no duplicate registration
            second.enable();
            assertEquals(2, server.handlers.getRegisteredListeners().length);
            first.disable();
            first.disable();
            assertEquals(1, server.handlers.getRegisteredListeners().length);
            assertSame(second, server.handlers.getRegisteredListeners()[0].getListener());
            for (int index = 0; index < 2; index++) {
                verify(server.scheduled.get(index).task()).cancel();
                server.scheduled.get(index).run();
            }
            assertEquals(0, calls.get());
            server.scheduled.get(2).run();
            assertEquals(1, calls.get());
            second.disable();
        }
    }

    @Test
    void oldCallbacksCannotRunAfterTheSameStateIsEnabledAgain() {
        try (var server = new StateTestServer()) {
            AtomicInteger calls = new AtomicInteger();
            class CallbackState extends AbstractGameState {
                CallbackState() { super(server.plugin); }
                Runnable callback() { return guard(calls::incrementAndGet); }
            }
            var state = new CallbackState();
            state.enable();
            Runnable stale = state.callback();
            state.disable();
            state.enable();
            stale.run();
            assertEquals(0, calls.get());
            state.callback().run();
            assertEquals(1, calls.get());
            state.disable();
        }
    }

    @Test
    void failedEntryStillUnregistersAndCancelsWork() {
        try (var server = new StateTestServer()) {
            var state = new AbstractGameState(server.plugin) {
                @Override protected void onEnable() {
                    runLater(() -> fail("Failed state's callback ran"), 1L);
                    throw new IllegalStateException("entry failed");
                }
            };
            assertThrows(IllegalStateException.class, state::enable);
            assertFalse(state.isEnabled());
            assertEquals(0, server.handlers.getRegisteredListeners().length);
            verify(server.scheduled.getFirst().task()).cancel();
            server.scheduled.getFirst().run();
        }
    }

    @Test
    void failedExitStillUnregistersAndCancelsWork() {
        try (var server = new StateTestServer()) {
            var state = new AbstractGameState(server.plugin) {
                @Override protected void onEnable() { runLater(() -> fail("Exited state's callback ran"), 1L); }
                @Override protected void onDisable() { throw new IllegalStateException("exit failed"); }
            };
            state.enable();
            assertThrows(IllegalStateException.class, state::disable);
            assertFalse(state.isEnabled());
            assertEquals(0, server.handlers.getRegisteredListeners().length);
            verify(server.scheduled.getFirst().task()).cancel();
            server.scheduled.getFirst().run();
        }
    }
}
