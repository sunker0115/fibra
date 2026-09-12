package com.sstlfsj.fibra.plugins.storage.tool;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.storage.ConfigChange;
import com.sstlfsj.fibra.plugins.storage.ConfigChangeListener;
import com.sstlfsj.fibra.plugins.storage.ConfigChangeOperation;
import com.sstlfsj.fibra.plugins.storage.ConfigDocument;
import com.sstlfsj.fibra.plugins.storage.ConfigStore;
import com.sstlfsj.fibra.plugins.storage.StorageErrorCode;
import com.sstlfsj.fibra.plugins.storage.StorageException;
import com.sstlfsj.fibra.plugins.storage.StorageServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageToolEntrypointTest {
    @Test
    void publishesFourFormalToolsWithClosedSchemasAndManifest() throws Exception {
        var definition = new StorageToolEntrypoint().definition();

        assertEquals(Void.class, definition.configType());
        assertEquals(java.util.Set.of(StorageServices.CONFIG_STORE, ContributionServices.REGISTRAR),
            definition.requires().keySet());
        var descriptors = StorageToolEntrypoint.descriptors();
        assertEquals(List.of("load", "put", "remove", "changes"), new ArrayList<>(descriptors.keySet()));
        assertEquals(List.of("revision", "values"),
            ((Map<?, ?>) descriptors.get("load").outputSchema().toJava()).get("required"));
        assertEquals(List.of("key", "value"),
            ((Map<?, ?>) descriptors.get("put").inputSchema().toJava()).get("required"));
        assertEquals(List.of("key"),
            ((Map<?, ?>) descriptors.get("remove").inputSchema().toJava()).get("required"));
        assertEquals(List.of("changes", "dropped"),
            ((Map<?, ?>) descriptors.get("changes").outputSchema().toJava()).get("required"));
        try (var input = StorageToolEntrypointTest.class.getResourceAsStream("/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("id: fibra-tool-storage"));
            assertTrue(manifest.contains("version: " + System.getProperty("fibra.test.projectVersion")));
            assertTrue(manifest.contains("entrypoint: " + StorageToolEntrypoint.class.getName()));
            assertTrue(manifest.contains("id: fibra-storage"));
            assertFalse(manifest.contains("${project.version}"));
        }
    }

    @Test
    void loadsPutsAndRemovesDocumentsWhileRejectingUnknownOrInvalidArguments() {
        var store = new FakeStore();
        try (var harness = new Harness(store)) {
            var loaded = harness.invoke("load", Map.of());
            assertEquals("{\"revision\":0,\"values\":{}}", loaded.structuredContent().orElseThrow().canonicalJson());
            assertEquals(List.of(com.sstlfsj.fibra.plugins.tool.ToolContent.text("{\"revision\":0,\"values\":{}}")), loaded.content());
            assertEquals("{\"revision\":1,\"values\":{\"theme\":\"dark\"}}",
                harness.invoke("put", Map.of("key", "theme", "value", "dark")).structuredContent().orElseThrow().canonicalJson());
            assertEquals("{\"revision\":2,\"values\":{}}",
                harness.invoke("remove", Map.of("key", "theme")).structuredContent().orElseThrow().canonicalJson());
            assertEquals("{\"revision\":2,\"values\":{}}",
                harness.invoke("remove", Map.of("key", "theme")).structuredContent().orElseThrow().canonicalJson());
            assertEquals(2, store.revision);
            assertEquals(2, store.emitted.size());
            assertFailure(ToolFailureCode.INVALID_ARGUMENT,
                () -> harness.invoke("put", Map.of("key", "theme", "value", "dark", "extra", true)));
            assertFailure(ToolFailureCode.INVALID_ARGUMENT,
                () -> harness.invoke("remove", Map.of("key", " ")));
            assertFailure(ToolFailureCode.INVALID_ARGUMENT,
                () -> harness.invoke("load", Map.of("key", "theme")));
        }
    }

    @Test
    void mapsStorageErrorsAndHonorsCancellationBeforeAndAfterCommit() {
        var store = new FakeStore();
        try (var harness = new Harness(store)) {
            store.putFailure = new StorageException(StorageErrorCode.VERSION_MISMATCH, "stale");
            assertFailure(ToolFailureCode.IO_ERROR,
                () -> harness.invoke("put", Map.of("key", "x", "value", true)));
            store.putFailure = new StorageException(StorageErrorCode.MALFORMED_DOCUMENT, "bad");
            assertFailure(ToolFailureCode.IO_ERROR,
                () -> harness.invoke("put", Map.of("key", "x", "value", true)));
            store.putFailure = new StorageException(StorageErrorCode.PERSISTENCE_FAILED, "disk");
            assertFailure(ToolFailureCode.IO_ERROR,
                () -> harness.invoke("put", Map.of("key", "x", "value", true)));
            store.putFailure = new StorageException(StorageErrorCode.CLOSED, "closed");
            assertFailure(ToolFailureCode.IO_ERROR,
                () -> harness.invoke("put", Map.of("key", "x", "value", true)));
            store.putFailure = null;

            var cancelled = new CancellationSource();
            cancelled.cancel();
            store.putInvoked.set(false);
            assertFailure(ToolFailureCode.ABORTED,
                () -> harness.invoke("put", Map.of("key", "x", "value", true), cancelled));
            assertTrue(store.values.isEmpty());
            assertFalse(store.putInvoked.get());

            var pending = Sinks.<ConfigDocument>one();
            store.pendingPut = pending;
            var cancellation = new CancellationSource();
            var result = harness.invokeAsync("put", Map.of("key", "x", "value", true), cancellation);
            assertTrue(store.putInvoked.get());
            cancellation.cancel();
            assertFalse(result.isDone());
            store.commitPendingPut("x", LiteralValue.of(true));
            var completion = assertThrows(CompletionException.class, result::join);
            assertEquals(ToolFailureCode.ABORTED,
                assertInstanceOf(ToolException.class, completion.getCause()).failure().code());
            assertEquals(LiteralValue.of(true), store.values.get("x"));
        }
    }

    @Test
    void reportsOnlyThisInstanceLiveBoundedChangeSnapshotAndDisposesSubscription() {
        var store = new FakeStore();
        store.emit(1, "before", ConfigChangeOperation.PUT, LiteralValue.of(true));
        try (var harness = new Harness(store)) {
            assertEquals(PluginInstanceState.ACTIVE, harness.plugin.state());
            assertEquals("{\"changes\":[],\"dropped\":false}", harness.invoke("changes", Map.of()).structuredContent().orElseThrow().canonicalJson());
            store.emit(2, "live", ConfigChangeOperation.PUT, LiteralValue.of(true));
            assertEquals("{\"changes\":[{\"key\":\"live\",\"operation\":\"PUT\",\"revision\":2,\"value\":true}],\"dropped\":false}",
                harness.invoke("changes", Map.of()).structuredContent().orElseThrow().canonicalJson());
            for (var revision = 3; revision <= 67; revision++) {
                store.emit(revision, "k" + revision, ConfigChangeOperation.PUT, LiteralValue.of(revision));
            }
            var snapshot = (Map<?, ?>) harness.invoke("changes", Map.of()).structuredContent().orElseThrow().toJava();
            assertEquals(true, snapshot.get("dropped"));
            var changes = (List<?>) snapshot.get("changes");
            assertEquals(64, changes.size());
            assertEquals(new java.math.BigDecimal("4"), ((Map<?, ?>) changes.getFirst()).get("revision"));
            assertEquals(new java.math.BigDecimal("67"), ((Map<?, ?>) changes.getLast()).get("revision"));
            harness.plugin.dispose().block();
            assertTrue(store.subscriptionDisposed.get());
        }
    }

    private static void assertFailure(ToolFailureCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ToolException.class, action).failure().code());
    }

    private static final class Harness implements AutoCloseable {
        private final FibraRuntime runtime = FibraRuntime.create();
        private final ContributionDirectory directory = new ContributionDirectory();
        private final com.sstlfsj.fibra.PluginInstance plugin;

        private Harness(ConfigStore store) {
            var context = runtime.rootScope().context();
            context.services().provide(StorageServices.CONFIG_STORE, store);
            context.services().provide(ContributionServices.REGISTRAR, directory);
            plugin = context.plugins().mount("storage-tools", new StorageToolEntrypoint().definition().prepare(null));
            plugin.settled().block();
        }

        private com.sstlfsj.fibra.plugins.tool.ToolResult invoke(String name, Map<String, ?> arguments) {
            return invoke(name, arguments, new CancellationSource());
        }

        private com.sstlfsj.fibra.plugins.tool.ToolResult invoke(String name, Map<String, ?> arguments,
                                                                  CancellationSource cancellation) {
            return directory.current().routes().invoke(runtime.rootScope().context(), ToolContributions.KIND,
                ToolContributions.id("storage-tools", name), ToolRequest.of(arguments, cancellation.token())).block();
        }

        private java.util.concurrent.CompletableFuture<com.sstlfsj.fibra.plugins.tool.ToolResult> invokeAsync(
            String name, Map<String, ?> arguments, CancellationSource cancellation) {
            return directory.current().routes().invoke(runtime.rootScope().context(), ToolContributions.KIND,
                ToolContributions.id("storage-tools", name), ToolRequest.of(arguments, cancellation.token())).toFuture();
        }

        @Override
        public void close() {
            runtime.close();
            directory.close();
        }
    }

    private static final class FakeStore implements ConfigStore {
        private final Map<String, LiteralValue> values = new LinkedHashMap<>();
        private final List<ConfigChange> emitted = new ArrayList<>();
        private final AtomicBoolean putInvoked = new AtomicBoolean();
        private final AtomicBoolean subscriptionDisposed = new AtomicBoolean();
        private ConfigChangeListener listener;
        private long revision;
        private StorageException putFailure;
        private Sinks.One<ConfigDocument> pendingPut;

        @Override
        public ConfigDocument load(InvocationContext context) {
            return new ConfigDocument(revision, values);
        }

        @Override
        public Mono<ConfigDocument> put(InvocationContext context, String key, LiteralValue value) {
            putInvoked.set(true);
            if (putFailure != null) return Mono.error(putFailure);
            if (pendingPut != null) return pendingPut.asMono();
            return Mono.just(commitPut(key, value));
        }

        @Override
        public Mono<ConfigDocument> remove(InvocationContext context, String key) {
            if (!values.containsKey(key)) return Mono.just(new ConfigDocument(revision, values));
            values.remove(key);
            var document = new ConfigDocument(++revision, values);
            emit(revision, key, ConfigChangeOperation.REMOVED, null);
            return Mono.just(document);
        }

        @Override
        public Disposable subscribe(InvocationContext context, ConfigChangeListener listener) {
            this.listener = listener;
            var registration = Disposables.from(() -> {
                this.listener = null;
                subscriptionDisposed.set(true);
            });
            context.effects().add(registration);
            return registration;
        }

        private ConfigDocument commitPut(String key, LiteralValue value) {
            values.put(key, value);
            var document = new ConfigDocument(++revision, values);
            emit(revision, key, ConfigChangeOperation.PUT, value);
            return document;
        }

        private void commitPendingPut(String key, LiteralValue value) {
            pendingPut.tryEmitValue(commitPut(key, value));
        }

        private void emit(long revision, String key, ConfigChangeOperation operation, LiteralValue value) {
            var change = new ConfigChange(revision, key, operation, value);
            emitted.add(change);
            if (listener != null) listener.changed(change);
        }
    }
}
