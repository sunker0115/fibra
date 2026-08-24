package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileCoordinatorTest {
    @Test
    void coalescesBurstsAndRunsAgainWhenDirtiedDuringExecution() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var coordinator = new ReconcileCoordinator(() -> {
            var call = calls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
            }
        }, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofMillis(50),
            ignored -> { })) {
            coordinator.start();
            for (int index = 0; index < 20; index++) {
                coordinator.request();
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 20; index++) {
                coordinator.request();
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
        try (var coordinator = new ReconcileCoordinator(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("retry");
            }
        }, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofMillis(20),
            ignored -> { })) {
            coordinator.start();
            coordinator.request();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(3, attempts.get()));
        }
    }

    @Test
    void serializesExplicitOperationsAndReconcilesDirtyStateAfterward() throws Exception {
        var sequence = new CopyOnWriteArrayList<String>();
        var reconcileEntered = new CountDownLatch(1);
        var releaseReconcile = new CountDownLatch(1);
        var deploymentEntered = new CountDownLatch(1);
        var releaseDeployment = new CountDownLatch(1);
        try (var coordinator = new ReconcileCoordinator(() -> {
            sequence.add("reconcile");
            if (reconcileEntered.getCount() > 0) {
                reconcileEntered.countDown();
                releaseReconcile.await(2, TimeUnit.SECONDS);
            }
        }, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofMillis(20),
            ignored -> { })) {
            coordinator.start();
            coordinator.request();
            assertTrue(reconcileEntered.await(2, TimeUnit.SECONDS));

            var deployment = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                coordinator.execute(() -> {
                    sequence.add("deployment");
                    deploymentEntered.countDown();
                    try {
                        releaseDeployment.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return "applied";
                }));
            releaseReconcile.countDown();
            assertTrue(deploymentEntered.await(2, TimeUnit.SECONDS));
            coordinator.request();
            releaseDeployment.countDown();

            assertEquals("applied", deployment.get(2, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertEquals(java.util.List.of("reconcile", "deployment", "reconcile"),
                    sequence));
        } finally {
            releaseReconcile.countDown();
            releaseDeployment.countDown();
        }
    }
}
