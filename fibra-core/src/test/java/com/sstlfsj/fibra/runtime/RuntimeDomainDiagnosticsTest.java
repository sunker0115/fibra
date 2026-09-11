package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDomainDiagnosticsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotCompletionAlwaysObservesAClosedDomain(boolean cleanupFails) {
        try (var runtime = FibraRuntime.create()) {
            for (var index = 0; index < 500; index++) {
                var domain = runtime.openDomain("closing-" + index);
                var cleanupAttempts = new AtomicInteger();
                if (cleanupFails) {
                    domain.rootScope().context().effects().add(() -> {
                        cleanupAttempts.incrementAndGet();
                        return Mono.error(new IllegalStateException("cleanup"));
                    });
                }
                var completed = Sinks.<Boolean>one();
                domain.snapshots().subscribe(ignored -> { }, completed::tryEmitError,
                    () -> completed.tryEmitValue(domain.isClosed()));
                // Scope 保留 Cordis 的顶层清理错误隔离语义；诊断流终态仍须在关闭状态之后。
                domain.closeAsync().block(TIMEOUT);
                assertEquals(cleanupFails ? 1 : 0, cleanupAttempts.get());
                assertTrue(completed.asMono().block(TIMEOUT), "completion preceded closure at " + index);
            }
        }
    }

    @Test
    void missingDependencyFactsChangeEvenWhenThePluginRemainsPending() {
        var first = ServiceKey.of("first", String.class);
        var second = ServiceKey.of("second", String.class);
        var definition = PluginDefinition.builder("consumer", Void.class,
            () -> (context, config) -> Mono.empty()).require(first).require(second).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("live");
            var instance = domain.rootScope().context().plugins().mount("consumer", definition.prepare(null));
            instance.settled().block(TIMEOUT);
            var waiting = domain.snapshots().filter(value -> value.plugins().size() == 1
                && value.plugins().getFirst().waitingFor().size() == 2).next().block(TIMEOUT);
            assertEquals(PluginInstanceState.PENDING, waiting.plugins().getFirst().state());

            var changed = domain.snapshots().filter(value -> value.plugins().size() == 1
                && value.plugins().getFirst().waitingFor().size() == 1).next().toFuture();
            domain.rootScope().context().services().provide(first, "available");
            var next = Mono.fromFuture(changed).block(TIMEOUT);
            assertEquals(PluginInstanceState.PENDING, next.plugins().getFirst().state());
            assertEquals(List.of("second"), next.plugins().getFirst().waitingFor().stream()
                .map(RuntimeDomainSnapshot.ServiceIdentity::name).toList());
            assertEquals(2, waiting.plugins().getFirst().waitingFor().size());
        }
    }

    @Test
    void closingPublishesFinalFactsAndCompletesWithoutClosingSiblingDomains() {
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("live");
            var sibling = runtime.openDomain("sibling");
            domain.rootScope().context().services().provide(ServiceKey.of("message", String.class), "value");
            var completed = domain.snapshots().collectList().toFuture();
            domain.closeAsync().block(TIMEOUT);
            var snapshots = Mono.fromFuture(completed).block(TIMEOUT);
            assertTrue(snapshots.getLast().services().isEmpty());
            assertEquals(snapshots.getLast(), domain.snapshots().blockLast(TIMEOUT));
            assertTrue(sibling.snapshots().next().block(TIMEOUT).services().isEmpty());
        }
    }
}
