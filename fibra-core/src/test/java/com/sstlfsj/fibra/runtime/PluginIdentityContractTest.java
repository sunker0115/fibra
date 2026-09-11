package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PluginDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginIdentityContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void sameLocalIdInDifferentScopesHasDifferentStableDiagnosticIdentity() {
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> Mono.empty()).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("identity");
            var first = domain.rootScope().context().plugins()
                .mount("shared", definition.prepare("first"));
            var second = domain.rootScope().openChild("child").context().plugins()
                .mount("shared", definition.prepare("second"));
            first.settled().block(TIMEOUT);
            second.settled().block(TIMEOUT);

            assertNotEquals(first.identity(), second.identity());
            var identities = domain.snapshot().plugins().stream()
                .map(RuntimeDomainSnapshot.Plugin::identity).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of(first.identity(), second.identity()), identities);

            var firstIdentity = first.identity();
            first.update("updated").block(TIMEOUT);
            assertEquals(firstIdentity, first.identity());
            assertEquals(firstIdentity, domain.snapshot().plugins().stream()
                .filter(value -> value.identity() == firstIdentity).findFirst().orElseThrow().identity());
        }
    }

    @Test
    void dynamicChildReportsTheActualParentPluginIdentity() {
        var childReference = new AtomicReference<com.sstlfsj.fibra.PluginInstance<Void>>();
        var child = PluginDefinition.builder("child", Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var parent = PluginDefinition.builder("parent", Void.class,
            () -> (context, config) -> {
                childReference.set(context.plugins().mount("child", child.prepare(null)));
                return Mono.empty();
            }).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("parents");
            var parentInstance = domain.rootScope().context().plugins()
                .mount("parent", parent.prepare(null));

            domain.settled().block(TIMEOUT);

            var observed = domain.snapshot().plugins();
            var parentSnapshot = observed.stream()
                .filter(value -> value.identity() == parentInstance.identity()).findFirst().orElseThrow();
            var childSnapshot = observed.stream()
                .filter(value -> value.identity() == childReference.get().identity()).findFirst().orElseThrow();
            assertNull(parentSnapshot.parentIdentity());
            assertEquals(Long.valueOf(parentInstance.identity()), childSnapshot.parentIdentity());
        }
    }
}
