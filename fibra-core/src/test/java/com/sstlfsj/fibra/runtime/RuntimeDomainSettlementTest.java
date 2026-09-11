package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeDomainSettlementTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void pendingPluginsAreAlreadySettled() {
        var dependency = ServiceKey.of("dependency", String.class);
        var definition = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> Mono.empty()).require(dependency).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("pending");
            var instance = domain.rootScope().context().plugins().mount("consumer",
                definition.prepare(null));

            domain.settled().block(TIMEOUT);

            assertEquals(PluginInstanceState.PENDING, instance.state());
        }
    }

    @Test
    void waitsForDynamicChildrenThatAreStillTransitioning() throws Exception {
        var releaseChild = Sinks.<Void>one();
        var childReference = new AtomicReference<com.sstlfsj.fibra.PluginInstance<Void>>();
        var child = PluginDefinition.builder("child", Void.class,
            () -> (context, config) -> releaseChild.asMono()).build();
        var parent = PluginDefinition.builder("parent", Void.class,
            () -> (context, config) -> {
                childReference.set(context.plugins().mount("child", child.prepare(null)));
                return Mono.empty();
            }).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("dynamic");
            var parentInstance = domain.rootScope().context().plugins()
                .mount("parent", parent.prepare(null));
            parentInstance.settled().block(TIMEOUT);
            var childInstance = childReference.get();
            var settled = domain.settled().toFuture();

            assertFalse(settled.isDone());
            releaseChild.tryEmitEmpty();
            settled.get(3, TimeUnit.SECONDS);
            assertEquals(PluginInstanceState.ACTIVE, childInstance.state());
        }
    }
}
