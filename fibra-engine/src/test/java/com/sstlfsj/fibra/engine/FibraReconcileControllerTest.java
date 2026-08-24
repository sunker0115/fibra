package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraReconcileControllerTest {
    @Test
    void coalescesBurstsAndRunsAgainWhenDirtiedDuringExecution() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var controller = new FibraReconcileController(() -> {
            var call = calls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
            }
        }, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofMillis(50),
            ignored -> { })) {
            controller.start();
            for (int index = 0; index < 20; index++) {
                controller.request();
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 20; index++) {
                controller.request();
            }
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(2, calls.get()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void retriesFailureWithBoundedBackoff() {
        var attempts = new AtomicInteger();
        try (var controller = new FibraReconcileController(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("retry");
            }
        }, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofMillis(20),
            ignored -> { })) {
            controller.start();
            controller.request();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(3, attempts.get()));
        }
    }
}
