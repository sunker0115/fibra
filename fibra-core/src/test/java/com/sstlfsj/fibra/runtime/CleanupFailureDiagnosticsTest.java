package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CleanupFailureDiagnosticsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void scopedFailuresFollowRealDescendantsAndRemainReadableAfterClose() {
        var definition = PluginDefinition.builder("broken-child", Void.class,
            () -> (context, config) -> {
                context.effects().effect(() -> () -> Mono.error(
                    new IllegalStateException("plugin cleanup failed")), "plugin");
                return Mono.empty();
            }).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("scoped-diagnostics");
            var selected = domain.rootScope().openChild("invocation");
            var descendant = selected.openChild("child");
            var unrelated = domain.rootScope().openChild("invocation");
            selected.context().effects().effect(() -> () -> Mono.error(
                new IllegalStateException("selected cleanup failed")), "selected");
            descendant.context().effects().effect(() -> () -> Mono.error(
                new IllegalStateException("descendant cleanup failed")), "descendant");
            unrelated.context().effects().effect(() -> () -> Mono.error(
                new IllegalStateException("unrelated cleanup failed")), "unrelated");
            descendant.context().plugins().mount("same-id", definition.prepare(null)).settled().block(TIMEOUT);
            unrelated.context().plugins().mount("same-id", definition.prepare(null)).settled().block(TIMEOUT);
            selected.closeAsync().block(TIMEOUT);
            unrelated.closeAsync().block(TIMEOUT);
            assertTrue(selected.isClosed(), "ordinary cleanup failure keeps the existing Scope contract");
            var selectedFailures = domain.cleanupFailures(selected);
            assertEquals(java.util.List.of("descendant", "plugin", "selected"), selectedFailures.stream()
                .map(RuntimeDomainSnapshot.CleanupFailure::resource).sorted().toList());
            assertEquals(2, domain.cleanupFailures(descendant).size());
            assertEquals(2, domain.cleanupFailures(unrelated).size());
            assertEquals(5, domain.cleanupFailures(domain.rootScope()).size());
            assertThrows(UnsupportedOperationException.class, () -> selectedFailures.clear());
            domain.closeAsync().block(TIMEOUT);
            runtime.closeAsync().block(TIMEOUT);
            assertEquals(selectedFailures, domain.cleanupFailures(selected));
        }
    }

    @Test
    void scopedFailuresRejectForeignScopesAndDoNotIncludeSiblingFailures() {
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("selected");
            var foreign = runtime.openDomain("foreign");
            var clean = domain.rootScope().openChild("clean");
            var dirty = domain.rootScope().openChild("dirty");
            dirty.context().effects().add(() -> Mono.error(new IllegalStateException("unrelated failure")));
            dirty.closeAsync().block(TIMEOUT);
            assertTrue(domain.cleanupFailures(clean).isEmpty());
            assertThrows(IllegalArgumentException.class,
                () -> domain.cleanupFailures(foreign.rootScope()));
        }
    }

    @Test
    void disposedPluginKeepsItsCleanupFailureAfterLeavingTheInstanceIndex() {
        var attempts = new AtomicInteger();
        var definition = PluginDefinition.builder("broken", Void.class,
            () -> (context, config) -> {
                context.effects().effect(() -> () -> {
                    attempts.incrementAndGet();
                    return Mono.error(new IllegalStateException("socket close failed"));
                }, "socket");
                return Mono.empty();
            }).build();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("diagnostics");
            var instance = domain.rootScope().context().plugins().mount("broken", definition.prepare(null));
            instance.settled().block(TIMEOUT);
            instance.dispose().block(TIMEOUT);
            assertEquals(PluginInstanceState.DISPOSED, instance.state());
            assertTrue(domain.snapshot().plugins().isEmpty());
            var failures = domain.snapshot().cleanupFailures();
            assertEquals(1, failures.size());
            assertEquals(instance.identity(), failures.getFirst().ownerIdentity());
            assertEquals("socket", failures.getFirst().resource());
            assertTrue(failures.getFirst().failure().contains("socket close failed"));
            domain.closeAsync().block(TIMEOUT);
            assertEquals(failures, domain.snapshot().cleanupFailures());
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void failedExplicitEffectCleanupIsRecordedAndNeverRetriedDuringScopeClose() {
        var attempts = new AtomicInteger();
        var untouched = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("diagnostics");
            Disposable first = () -> Mono.fromRunnable(untouched::incrementAndGet);
            Disposable second = () -> {
                attempts.incrementAndGet();
                return Mono.error(new IllegalArgumentException("broken disposer"));
            };
            var effect = domain.rootScope().context().effects().collect(Flux.just(first, second), "pair");
            effect.ready().block(TIMEOUT);
            var failure = assertThrows(IllegalArgumentException.class, () -> effect.dispose().block(TIMEOUT));
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> effect.dispose().block(TIMEOUT)));
            assertEquals(1, domain.snapshot().cleanupFailures().size());
            domain.closeAsync().block(TIMEOUT);
            assertEquals(1, attempts.get());
            assertEquals(0, untouched.get(), "failed prerequisite must preserve later disposers");
            assertEquals(1, domain.snapshot().cleanupFailures().size());
        }
    }
}
