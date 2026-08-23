package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderOperationGateTest {

    @Test
    void loaderIdentitySnapshotsRemainReadableWhileManagementIsBusy(@TempDir Path pluginsRoot)
        throws Exception {
        try (var root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, pluginsRoot);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            loader.loadArtifacts();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var owner = executor.submit(() -> loader.runExclusive(() -> {
                entered.countDown();
                await(release);
                return null;
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            assertEquals(List.of(), loader.artifactIds());
            assertEquals(List.of(), loader.entryIds());
            assertThrows(FibraPluginLoaderBusyException.class,
                () -> loader.fibra("missing"));
            assertThrows(FibraPluginLoaderBusyException.class, loader::close);

            release.countDown();
            owner.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void lifecycleThreadCanReadIdentitySnapshotButCannotEnterManagement(@TempDir Path pluginsRoot) {
        try (var root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, pluginsRoot)) {
            loader.loadArtifacts();

            root.plugin("callback", (context, config) -> {
                assertEquals(List.of(), loader.artifactIds());
                assertEquals(List.of(), loader.entryIds());
                assertThrows(FibraPluginLoaderBusyException.class,
                    () -> loader.fibra("missing"));
                assertThrows(FibraPluginLoaderBusyException.class,
                    () -> loader.runExclusive(() -> null));
                return Mono.just(Disposables.noop());
            }, null);
        }
    }

    @Test
    void allowsSameBlockingThreadToNestAndCommitsOnlyOutermostSuccess() {
        var gate = new LoaderOperationGate();
        var next = new LoaderOperationGate.IdentitySnapshot(
            Map.of("provider", "2.0.0"), List.of("first"));

        var result = gate.runExclusive(() -> gate.runExclusive(() -> "done", () -> next),
            () -> next);

        assertEquals("done", result);
        assertEquals(next, gate.snapshot());

        assertThrows(IllegalStateException.class, () -> gate.runExclusive(() -> {
            throw new IllegalStateException("failed");
        }, LoaderOperationGate.IdentitySnapshot::empty));
        assertEquals(next, gate.snapshot());
    }

    @Test
    void competingThreadFailsImmediatelyWhileOwnerActionRunsWithoutPhysicalLock()
        throws Exception {
        var gate = new LoaderOperationGate();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> gate.runExclusive(() -> {
                entered.countDown();
                await(release);
                return null;
            }, LoaderOperationGate.IdentitySnapshot::empty));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            var started = System.nanoTime();
            assertThrows(FibraPluginLoaderBusyException.class,
                () -> gate.runExclusive(() -> null,
                    LoaderOperationGate.IdentitySnapshot::empty));
            assertTrue(Duration.ofNanos(System.nanoTime() - started)
                .compareTo(Duration.ofMillis(200)) < 0);
            assertEquals(LoaderOperationGate.IdentitySnapshot.empty(), gate.snapshot());

            release.countDown();
            owner.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsReactorNonBlockingThread() {
        var gate = new LoaderOperationGate();

        var error = assertThrows(FibraPluginLoaderBusyException.class,
            () -> Mono.fromCallable(() -> gate.runExclusive(() -> "bad",
                    LoaderOperationGate.IdentitySnapshot::empty))
                .subscribeOn(Schedulers.single())
                .block());

        assertTrue(error.getMessage().contains("non-blocking"));
    }

    @Test
    void closeRejectsNestedOrCompetingTransactionAndCanRetryAfterFailure() throws Exception {
        var gate = new LoaderOperationGate();

        gate.runExclusive(() -> {
            assertThrows(FibraPluginLoaderBusyException.class, () -> gate.close(() -> {
            }));
            return null;
        }, LoaderOperationGate.IdentitySnapshot::empty);

        assertThrows(IllegalStateException.class, () -> gate.close(() -> {
            throw new IllegalStateException("close failed");
        }));
        assertFalse(gate.isClosed());

        gate.close(() -> {
        });
        assertTrue(gate.isClosed());
        assertThrows(IllegalStateException.class, gate::snapshot);
        gate.close(() -> {
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
