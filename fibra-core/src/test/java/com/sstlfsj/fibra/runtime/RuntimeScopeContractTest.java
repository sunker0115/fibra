package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeScopeContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MESSAGE = ServiceKey.of("message", String.class);

    @Test
    void derivedContextsShareOwnershipWithoutBecomingCloseable() {
        try (var runtime = FibraRuntime.create();
             var scope = runtime.rootScope().openChild("session")) {
            var base = scope.context();
            var derived = base.withMetadata("request", "r-1")
                .withRealm(MESSAGE, "tenant-a")
                .withIntercept(MESSAGE, "trace");

            assertSame(scope, derived.scope());
            assertFalse(derived instanceof AutoCloseable);
        }
    }

    @Test
    void closingChildDisposesItsTreeWithoutAffectingSiblingOrRoot() {
        var disposed = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var first = runtime.rootScope().openChild("first");
            var nested = first.openChild("nested");
            var sibling = runtime.rootScope().openChild("sibling");
            runtime.rootScope().context().services().provide(MESSAGE, "root");
            nested.context().effects().add(Disposables.from(disposed::incrementAndGet));

            first.closeAsync().block(TIMEOUT);

            assertEquals(1, disposed.get());
            assertEquals("root", sibling.context().services().require(MESSAGE));
            assertFalse(sibling.isClosed());
            sibling.close();
        }
    }

    @Test
    void runtimeCloseIsFinalAndRejectsNewOwnership() {
        var runtime = FibraRuntime.create();
        runtime.closeAsync().block(TIMEOUT);

        assertTrue(runtime.isClosed());
        assertTrue(runtime.rootScope().isClosed());
        assertThrows(IllegalStateException.class,
            () -> runtime.rootScope().openChild("late"));
    }

    @Test
    void closingRootScopeClosesTheRuntime() {
        var runtime = FibraRuntime.create();

        runtime.rootScope().closeAsync().block(TIMEOUT);

        assertTrue(runtime.isClosed());
    }

    @Test
    void closeAndPluginSnapshotsRemainIdempotentAfterSchedulerShutdown() {
        var runtime = FibraRuntime.create();
        var definition = PluginDefinition.builder("snapshot", String.class,
            () -> (context, config) -> reactor.core.publisher.Mono.empty()).build();
        var instance = runtime.rootScope().context().plugins()
            .mount("snapshot-1", definition, "value");
        instance.settled().block(TIMEOUT);

        runtime.closeAsync().block(TIMEOUT);

        assertEquals(PluginInstanceState.DISPOSED, instance.state());
        assertEquals("value", instance.config());
        assertTrue(instance.failure().isEmpty());
        assertSame(instance, instance.settled().block(TIMEOUT));
        instance.dispose().block(TIMEOUT);
        runtime.closeAsync().block(TIMEOUT);
        runtime.rootScope().closeAsync().block(TIMEOUT);
    }
}
