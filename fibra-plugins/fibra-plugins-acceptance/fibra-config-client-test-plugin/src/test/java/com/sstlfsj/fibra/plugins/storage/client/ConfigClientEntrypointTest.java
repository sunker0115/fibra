package com.sstlfsj.fibra.plugins.storage.client;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.storage.ConfigChange;
import com.sstlfsj.fibra.plugins.storage.ConfigChangeListener;
import com.sstlfsj.fibra.plugins.storage.ConfigChangeOperation;
import com.sstlfsj.fibra.plugins.storage.ConfigDocument;
import com.sstlfsj.fibra.plugins.storage.ConfigStore;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigClientEntrypointTest {
    @Test
    void declaresOnlyPublicServiceDependenciesAndPublishesARealToolContribution() {
        var definition = new ConfigClientEntrypoint().definition();
        assertTrue(definition.requires().containsKey(StorageServices.CONFIG_STORE));
        assertTrue(definition.requires().containsKey(ContributionServices.REGISTRAR));
        assertTrue(definition.provides().isEmpty());

        var store = new FakeStore();
        var directory = new ContributionDirectory();
        var runtime = FibraRuntime.create();
        var context = runtime.rootScope().context();
        context.services().provide(StorageServices.CONFIG_STORE, store);
        context.services().provide(ContributionServices.REGISTRAR, directory);
        var plugin = context.plugins().mount("mounted-client",
            definition.prepare(new ConfigClientConfig()));
        plugin.settled().block();
        var invocationScope = runtime.rootScope().openChild("tool-invocation");
        var cancellation = new CancellationSource();

        try {
            var id = ToolContributions.id("mounted-client", ConfigClientEntrypoint.LOCAL_NAME);
            var put = directory.current().routes().invoke(invocationScope.context(),
                ToolContributions.KIND, id,
                ToolRequest.of(Map.of("operation", "put", "key", "theme", "value", "dark"),
                    cancellation.token()))
                .block();
            var load = directory.current().routes().invoke(invocationScope.context(),
                ToolContributions.KIND, id,
                ToolRequest.of(Map.of("operation", "load"), cancellation.token())).block();

            assertEquals("{\"document\":{\"revision\":1,\"values\":{\"theme\":\"dark\"}},"
                    + "\"events\":[{\"key\":\"theme\",\"operation\":\"PUT\",\"revision\":1,"
                    + "\"value\":\"dark\"}]}",
                put.data().canonicalJson());
            assertEquals(put.data(), load.data());
            assertSame(plugin.context(), store.lastInvocation.caller());
            assertSame(invocationScope, store.lastInvocation.scope());
            assertSame(cancellation.token(), store.lastInvocation.cancellation());
            assertFalse(store.invocationDisposed.get());
            assertFalse(store.subscriptionDisposed.get());
        } finally {
            invocationScope.close();
            runtime.close();
            directory.close();
        }
        assertTrue(store.invocationDisposed.get());
        assertTrue(store.subscriptionDisposed.get());
    }

    @Test
    void manifestRequiresOnlyTheFrozenStorageContract() throws Exception {
        try (var input = ConfigClientEntrypoint.class.getResourceAsStream(
            "/META-INF/fibra/plugin.yaml")) {
            var manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("id: fibra-config-client-test-plugin"));
            assertTrue(manifest.contains("entrypoint: " + ConfigClientEntrypoint.class.getName()));
            assertTrue(manifest.contains("id: fibra-storage"));
            assertFalse(manifest.contains("id: fibra-tool-api"));
        }
    }

    @Test
    void preCancelledToolRequestDoesNotReachTheStore() {
        var store = new FakeStore();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var context = runtime.rootScope().context();
            context.services().provide(StorageServices.CONFIG_STORE, store);
            context.services().provide(ContributionServices.REGISTRAR, directory);
            var plugin = context.plugins().mount("client",
                new ConfigClientEntrypoint().definition().prepare(new ConfigClientConfig()));
            plugin.settled().block();
            var cancellation = new CancellationSource();
            cancellation.cancel();

            var failure = assertThrows(ToolException.class, () ->
                directory.current().routes().invoke(context, ToolContributions.KIND,
                    ToolContributions.id("client", ConfigClientEntrypoint.LOCAL_NAME),
                    ToolRequest.of(Map.of("operation", "put", "key", "x", "value", true),
                        cancellation.token())).block());

            assertEquals(ToolFailureCode.ABORTED, failure.code());
            assertTrue(store.values.isEmpty());
        }
    }

    @Test
    void cancellationAfterAnAcceptedWriteWaitsForCommitThenReturnsAborted() {
        var store = new FakeStore();
        store.pendingPut = Sinks.one();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var context = runtime.rootScope().context();
            context.services().provide(StorageServices.CONFIG_STORE, store);
            context.services().provide(ContributionServices.REGISTRAR, directory);
            var plugin = context.plugins().mount("client",
                new ConfigClientEntrypoint().definition().prepare(new ConfigClientConfig()));
            plugin.settled().block();
            var cancellation = new CancellationSource();

            var result = directory.current().routes().invoke(context, ToolContributions.KIND,
                ToolContributions.id("client", ConfigClientEntrypoint.LOCAL_NAME),
                ToolRequest.of(Map.of("operation", "put", "key", "x", "value", true),
                    cancellation.token())).toFuture();

            assertTrue(store.putInvoked.get());
            assertFalse(result.isDone());
            cancellation.cancel();
            assertFalse(result.isDone());
            store.completePendingPut("x", LiteralValue.of(true));

            var completion = assertThrows(CompletionException.class, result::join);
            var failure = assertInstanceOf(ToolException.class, completion.getCause());
            assertEquals(ToolFailureCode.ABORTED, failure.code());
            assertEquals(LiteralValue.of(true), store.values.get("x"));
        }
    }

    private static final class FakeStore implements ConfigStore {
        private final Map<String, LiteralValue> values = new LinkedHashMap<>();
        private final AtomicBoolean subscriptionDisposed = new AtomicBoolean();
        private final AtomicBoolean invocationDisposed = new AtomicBoolean();
        private final AtomicBoolean putInvoked = new AtomicBoolean();
        private ConfigChangeListener listener;
        private Sinks.One<ConfigDocument> pendingPut;
        private long revision;
        private InvocationContext lastInvocation;

        @Override
        public ConfigDocument load(InvocationContext context) {
            lastInvocation = context;
            context.effects().add(Disposables.from(() -> invocationDisposed.set(true)));
            return new ConfigDocument(revision, values);
        }

        @Override
        public Mono<ConfigDocument> put(InvocationContext context, String key, LiteralValue value) {
            lastInvocation = context;
            putInvoked.set(true);
            if (pendingPut != null) return pendingPut.asMono();
            values.put(key, value);
            var document = new ConfigDocument(++revision, values);
            if (listener != null) {
                listener.changed(new ConfigChange(revision, key, ConfigChangeOperation.PUT, value));
            }
            return Mono.just(document);
        }

        private void completePendingPut(String key, LiteralValue value) {
            values.put(key, value);
            var document = new ConfigDocument(++revision, values);
            if (listener != null) {
                listener.changed(new ConfigChange(revision, key, ConfigChangeOperation.PUT, value));
            }
            pendingPut.tryEmitValue(document);
        }

        @Override
        public Mono<ConfigDocument> remove(InvocationContext context, String key) {
            lastInvocation = context;
            values.remove(key);
            var document = new ConfigDocument(++revision, values);
            if (listener != null) {
                listener.changed(new ConfigChange(revision, key, ConfigChangeOperation.REMOVED, null));
            }
            return Mono.just(document);
        }

        @Override
        public Disposable subscribe(InvocationContext context, ConfigChangeListener listener) {
            lastInvocation = context;
            this.listener = listener;
            var registration = Disposables.from(() -> {
                this.listener = null;
                subscriptionDisposed.set(true);
            });
            context.effects().add(registration);
            return registration;
        }
    }
}
