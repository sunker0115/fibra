package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventModeContractTest {
    @Test
    void rejectsDispatchThroughAModeDifferentFromTheDeclaredContract() {
        var key = EventKey.of("changed", Signal.class, EventMode.EMIT);
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(key, () -> { });

            var failure = assertThrows(IllegalArgumentException.class,
                () -> events.parallel(key, listener -> Mono.fromRunnable(listener::call))
                    .block());

            assertEquals("event \"changed\" declares EMIT but was dispatched as PARALLEL",
                failure.getMessage());
        }
    }

    @Test
    void rejectsASecondContractWithTheSameNameAndDifferentMode() {
        var emitted = EventKey.of("changed", Signal.class, EventMode.EMIT);
        var bailed = EventKey.of("changed", Signal.class, EventMode.BAIL);
        try (var runtime = FibraRuntime.create()) {
            var events = runtime.rootScope().context().events();
            events.on(emitted, () -> { });

            var failure = assertThrows(IllegalArgumentException.class,
                () -> events.on(bailed, () -> { }));

            assertEquals("event \"changed\" has conflicting contracts",
                failure.getMessage());
        }
    }

    @FunctionalInterface
    interface Signal {
        void call();
    }
}
