package com.sstlfsj.fibra.plugins.storage.json;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.plugins.storage.ConfigChange;
import com.sstlfsj.fibra.plugins.storage.StorageErrorCode;
import com.sstlfsj.fibra.plugins.storage.StorageException;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonConfigStoreTest {
    @TempDir
    Path work;

    @Test
    void missingDocumentIsEmptyAndMaterializesOnlyOnFirstSuccessfulWrite() throws Exception {
        var target = work.resolve("realm/config.json");
        var store = new JsonConfigStore(target, logger(new AtomicInteger()));
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);

            assertEquals(0, store.load(invocation).revision());
            assertTrue(store.load(invocation).values().isEmpty());
            assertFalse(Files.exists(target));

            var written = store.put(invocation, "nested",
                LiteralValue.of(Map.of("enabled", true, "items", List.of(1, "two")))).block();

            assertEquals(1, written.revision());
            assertTrue(Files.exists(target));
            assertTrue(Files.readString(target).contains("\"formatVersion\":1"));
        } finally {
            store.dispose().block(Duration.ofSeconds(3));
        }

        var reopened = new JsonConfigStore(target, logger(new AtomicInteger()));
        try (var runtime = FibraRuntime.create()) {
            var loaded = reopened.load(invocation(runtime));
            assertEquals(1, loaded.revision());
            assertEquals("{\"enabled\":true,\"items\":[1,\"two\"]}",
                loaded.find("nested").orElseThrow().canonicalJson());
        } finally {
            reopened.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void malformedAndForeignVersionDocumentsFailWithStableCodes() throws Exception {
        var target = work.resolve("config.json");
        Files.writeString(target, "not-json");
        assertCode(StorageErrorCode.MALFORMED_DOCUMENT,
            () -> new JsonConfigStore(target, logger(new AtomicInteger())));

        Files.writeString(target,
            "{\"formatVersion\":2,\"revision\":0,\"values\":{}}\n");
        assertCode(StorageErrorCode.VERSION_MISMATCH,
            () -> new JsonConfigStore(target, logger(new AtomicInteger())));

        Files.write(target, new byte[] {
            '{', '"', 'f', 'o', 'r', 'm', 'a', 't', 'V', 'e', 'r', 's', 'i', 'o', 'n', '"', ':', '1', ',',
            '"', 'r', 'e', 'v', 'i', 's', 'i', 'o', 'n', '"', ':', '0', ',',
            '"', 'v', 'a', 'l', 'u', 'e', 's', '"', ':', '{', '"', 'x', '"', ':', '"',
            (byte) 0xc3, '(', '"', '}', '}'
        });
        assertCode(StorageErrorCode.MALFORMED_DOCUMENT,
            () -> new JsonConfigStore(target, logger(new AtomicInteger())));

        Files.writeString(target,
            "{\"formatVersion\":1,\"revision\":0,\"values\":{\"\":true}}\n");
        assertCode(StorageErrorCode.MALFORMED_DOCUMENT,
            () -> new JsonConfigStore(target, logger(new AtomicInteger())));
    }

    @Test
    void entrypointPublishesTheStoreAndManifestDeclaresTheContractEdge() throws Exception {
        var definition = new JsonStorageEntrypoint().definition();
        assertTrue(definition.provides().contains(
            com.sstlfsj.fibra.plugins.storage.StorageServices.CONFIG_STORE));
        assertTrue(definition.requires().isEmpty());

        var runtime = FibraRuntime.create();
        var context = runtime.rootScope().context();
        definition.factory().create().start(context, new JsonStorageConfig(work.toString())).block();
        var store = context.services().require(
            com.sstlfsj.fibra.plugins.storage.StorageServices.CONFIG_STORE);
        var invocation = InvocationContext.of(context, "entrypoint-test");
        assertEquals(0, store.load(invocation).revision());
        runtime.close();
        assertCode(StorageErrorCode.CLOSED, () -> store.load(invocation));

        try (var input = JsonStorageEntrypoint.class.getResourceAsStream(
            "/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("entrypoint: " + JsonStorageEntrypoint.class.getName()
                + "\n", manifest);
        }
    }

    @Test
    void concurrentWritesCommitAndNotifyInRevisionOrder() throws Exception {
        var io = new BlockingStorageIo();
        var store = new JsonConfigStore(work.resolve("config.json"),
            logger(new AtomicInteger()), io);
        var revisions = new ArrayList<Long>();
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            store.subscribe(invocation, change -> revisions.add(change.revision()));

            var first = store.put(invocation, "first", LiteralValue.of(1)).toFuture();
            assertTrue(io.started.await(3, TimeUnit.SECONDS));
            var second = store.put(invocation, "second", LiteralValue.of(2)).toFuture();
            assertFalse(second.isDone());
            io.release.countDown();

            assertEquals(1, first.get(3, TimeUnit.SECONDS).revision());
            assertEquals(2, second.get(3, TimeUnit.SECONDS).revision());
            assertEquals(List.of(1L, 2L), revisions);
            assertEquals(2, store.load(invocation).values().size());
        } finally {
            io.release.countDown();
            store.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void failedPublishDoesNotChangeMemoryOrEmitAndTheQueueRecovers() {
        var io = new FailOnceStorageIo();
        var store = new JsonConfigStore(work.resolve("config.json"),
            logger(new AtomicInteger()), io);
        var changes = new ArrayList<ConfigChange>();
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            store.subscribe(invocation, changes::add);

            assertCode(StorageErrorCode.PERSISTENCE_FAILED,
                () -> store.put(invocation, "failed", LiteralValue.of(true)).block());
            assertEquals(0, store.load(invocation).revision());
            assertTrue(store.load(invocation).values().isEmpty());
            assertTrue(changes.isEmpty());

            var recovered = store.put(invocation, "working", LiteralValue.of(true)).block();
            assertEquals(1, recovered.revision());
            assertEquals(List.of(1L), changes.stream().map(ConfigChange::revision).toList());
        } finally {
            store.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void postPublishDirectorySyncFailureKeepsMemoryEventsAndRestartConsistent() {
        var warnings = new AtomicInteger();
        var target = work.resolve("config.json");
        var store = new JsonConfigStore(target, logger(warnings),
            new PostPublishWarningStorageIo());
        var changes = new ArrayList<ConfigChange>();
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            store.subscribe(invocation, changes::add);

            var written = store.put(invocation, "published", LiteralValue.of(true)).block();

            assertEquals(1, written.revision());
            assertEquals(written, store.load(invocation));
            assertEquals(List.of(1L), changes.stream().map(ConfigChange::revision).toList());
            assertEquals(1, warnings.get());
        } finally {
            store.dispose().block(Duration.ofSeconds(3));
        }

        var reopened = new JsonConfigStore(target, logger(new AtomicInteger()));
        try (var runtime = FibraRuntime.create()) {
            assertEquals(1, reopened.load(invocation(runtime)).revision());
        } finally {
            reopened.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void listenerFailureIsLoggedAndIsolatedWhileTheReturnedHandleIsIdempotent() {
        var warnings = new AtomicInteger();
        var store = new JsonConfigStore(work.resolve("config.json"), logger(warnings));
        var delivered = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            var failed = store.subscribe(invocation, change -> {
                throw new IllegalStateException("listener failed");
            });
            var active = store.subscribe(invocation, change -> delivered.incrementAndGet());

            assertEquals(1, store.put(invocation, "key", LiteralValue.of("one")).block().revision());
            assertEquals(1, warnings.get());
            assertEquals(1, delivered.get());

            failed.dispose().block();
            failed.dispose().block();
            active.dispose().block();
            active.dispose().block();
            store.put(invocation, "key", LiteralValue.of("two")).block();
            assertEquals(1, delivered.get());
        } finally {
            store.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void callerScopeOwnsSubscriptions() {
        var store = new JsonConfigStore(work.resolve("config.json"),
            logger(new AtomicInteger()));
        var delivered = new AtomicInteger();
        var runtime = FibraRuntime.create();
        var invocation = invocation(runtime);
        store.subscribe(invocation, change -> delivered.incrementAndGet());
        runtime.close();

        try {
            store.put(invocation, "key", LiteralValue.of(true)).block();
            assertEquals(0, delivered.get());
        } finally {
            store.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void storeInstancesKeepStateAndListenersIsolated() {
        var first = new JsonConfigStore(work.resolve("first/config.json"),
            logger(new AtomicInteger()));
        var second = new JsonConfigStore(work.resolve("second/config.json"),
            logger(new AtomicInteger()));
        var firstEvents = new AtomicInteger();
        var secondEvents = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            first.subscribe(invocation, change -> firstEvents.incrementAndGet());
            second.subscribe(invocation, change -> secondEvents.incrementAndGet());

            first.put(invocation, "same-key", LiteralValue.of("first")).block();

            assertEquals(1, first.load(invocation).revision());
            assertEquals(0, second.load(invocation).revision());
            assertEquals(1, firstEvents.get());
            assertEquals(0, secondEvents.get());
        } finally {
            first.dispose().block(Duration.ofSeconds(3));
            second.dispose().block(Duration.ofSeconds(3));
        }
    }

    @Test
    void drainRejectsNewOperationsAndWaitsForAcceptedWriteAndNotification() throws Exception {
        var io = new BlockingStorageIo();
        var store = new JsonConfigStore(work.resolve("config.json"),
            logger(new AtomicInteger()), io);
        var notificationStarted = new CountDownLatch(1);
        var notificationRelease = new CountDownLatch(1);
        try (var runtime = FibraRuntime.create()) {
            var invocation = invocation(runtime);
            store.subscribe(invocation, change -> {
                notificationStarted.countDown();
                try {
                    if (!notificationRelease.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test notification release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", failure);
                }
            });
            var write = store.put(invocation, "accepted", LiteralValue.of(true)).toFuture();
            assertTrue(io.started.await(3, TimeUnit.SECONDS));

            var drained = store.drain().toFuture();
            assertFalse(drained.isDone());
            assertCode(StorageErrorCode.CLOSED, () -> store.load(invocation));
            assertCode(StorageErrorCode.CLOSED,
                () -> store.put(invocation, "late", LiteralValue.of(true)).block());
            assertCode(StorageErrorCode.CLOSED,
                () -> store.subscribe(invocation, change -> { }));

            io.release.countDown();
            assertTrue(notificationStarted.await(3, TimeUnit.SECONDS));
            assertFalse(drained.isDone());
            notificationRelease.countDown();
            assertEquals(1, write.get(3, TimeUnit.SECONDS).revision());
            drained.get(3, TimeUnit.SECONDS);
            store.drain().block(Duration.ofSeconds(3));
        } finally {
            io.release.countDown();
            notificationRelease.countDown();
            store.dispose().block(Duration.ofSeconds(3));
            store.dispose().block(Duration.ofSeconds(3));
        }
    }

    private static InvocationContext invocation(FibraRuntime runtime) {
        return InvocationContext.of(runtime.rootScope().context(), "storage-test");
    }

    private static FibraLogger logger(AtomicInteger warnings) {
        return (FibraLogger) Proxy.newProxyInstance(JsonConfigStoreTest.class.getClassLoader(),
            new Class<?>[] {FibraLogger.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("name")) return "test";
                if (method.getName().equals("warn")) warnings.incrementAndGet();
                return null;
            });
    }

    private static void assertCode(StorageErrorCode expected, Runnable action) {
        assertEquals(expected, assertThrows(StorageException.class, action::run).code());
    }

    private static class DelegatingStorageIo implements JsonConfigStore.StorageIo {
        final JsonConfigStore.StorageIo delegate = JsonConfigStore.defaultStorageIo();

        @Override
        public JsonConfigStore.StorageWriteResult writeAtomic(Path target, byte[] content)
            throws IOException {
            return delegate.writeAtomic(target, content);
        }
    }

    private static final class BlockingStorageIo extends DelegatingStorageIo {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public JsonConfigStore.StorageWriteResult writeAtomic(Path target, byte[] content)
            throws IOException {
            if (calls.getAndIncrement() == 0) {
                started.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("test release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", failure);
                }
            }
            return super.writeAtomic(target, content);
        }
    }

    private static final class FailOnceStorageIo extends DelegatingStorageIo {
        private boolean failed;

        @Override
        public JsonConfigStore.StorageWriteResult writeAtomic(Path target, byte[] content)
            throws IOException {
            if (!failed) {
                failed = true;
                throw new IOException("injected persistence failure");
            }
            return super.writeAtomic(target, content);
        }
    }

    private static final class PostPublishWarningStorageIo extends DelegatingStorageIo {
        @Override
        public JsonConfigStore.StorageWriteResult writeAtomic(Path target, byte[] content)
            throws IOException {
            super.writeAtomic(target, content);
            return new JsonConfigStore.StorageWriteResult(
                new IOException("injected directory sync failure"));
        }
    }
}
