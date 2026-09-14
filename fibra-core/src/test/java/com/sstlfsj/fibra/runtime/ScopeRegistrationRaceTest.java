package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.FibraException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeRegistrationRaceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void registrationAndCloseNeverLeaveAnUnownedResource() throws Exception {
        try (var runtime = FibraRuntime.create()) {
            for (int attempt = 0; attempt < 100; attempt++) {
                var scope = runtime.rootScope().openChild("race-" + attempt);
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var disposed = new AtomicInteger();
                var failure = new AtomicReference<Throwable>();

                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var registration = executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                            "registration task must be released");
                        try {
                            scope.context().effects().add(
                                Disposables.from(disposed::incrementAndGet));
                        } catch (FibraException expected) {
                            failure.set(expected);
                        }
                        return null;
                    });
                    var closing = executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                            "close task must be released");
                        scope.closeAsync().block(TIMEOUT);
                        return null;
                    });
                    try {
                        assertTrue(ready.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                            "registration and close tasks must both be ready");
                    } finally {
                        start.countDown();
                    }
                    registration.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    closing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }

                scope.closeAsync().block(TIMEOUT);
                assertTrue(failure.get() == null
                    || FibraException.SCOPE_CLOSED.equals(((FibraException) failure.get()).code()));
                assertEquals(failure.get() == null ? 1 : 0, disposed.get());
            }
        }
    }
}
