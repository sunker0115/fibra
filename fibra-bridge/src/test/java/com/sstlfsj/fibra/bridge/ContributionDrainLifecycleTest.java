package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContributionDrainLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<String, String, String> COMMAND =
        ContributionKind.local("command", String.class, String.class, String.class);

    @Test
    void dynamicChildCallsPinAllParentResourcesUntilTheLeaseIsReleased() throws Exception {
        var released = new AtomicInteger();
        var child = new AtomicReference<PluginInstance<Void>>();
        try (var runtime = FibraRuntime.create(); var directory = new ContributionDirectory()) {
            var childDefinition = PluginDefinition.builder("child", Void.class,
                () -> (context, config) -> directory.register(context, COMMAND, "child", "call", "child",
                    (invocation, input) -> Mono.just(input)).then()).build();
            var definition = PluginDefinition.builder("parent", Void.class,
                () -> (context, config) -> {
                    child.set(context.plugins().mount("child", childDefinition.prepare(null)));
                    context.effects().add(() -> Mono.fromRunnable(released::incrementAndGet));
                    return Mono.empty();
                }).build();
            var parent = runtime.rootScope().context().plugins().mount("parent", definition.prepare(null));
            parent.settled().block(TIMEOUT);
            child.get().settled().block(TIMEOUT);
            var routes = directory.current().routes();
            var id = new ContributionId("child", "call");
            var call = routes.acquire(COMMAND, id);
            var closing = parent.dispose().toFuture();
            try {
                runtime.rootScope().context().plugins().instances();
                assertEquals(0, released.get());
                assertFalse(closing.isDone());
                assertThrows(ContributionUnavailableException.class, () -> routes.acquire(COMMAND, id));
                assertThrows(RuntimeException.class, () -> child.get().context().plugins()
                    .mount("late-child", childDefinition.prepare(null)));
            } finally {
                call.close();
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, released.get());
        }
    }
}
