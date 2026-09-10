package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.event.EventOptions;
import com.sstlfsj.fibra.event.EventTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDomainIsolationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MESSAGE =
        ServiceKey.of("message", String.class);
    private static final EventKey<Signal> SIGNAL =
        EventKey.of("signal", Signal.class, EventMode.EMIT);

    @Test
    void isolatesServicesEventsAndClosureWhileSharingOneRuntime() {
        try (var runtime = FibraRuntime.create()) {
            var first = runtime.openDomain("generation-1");
            var second = runtime.openDomain("generation-2");
            first.rootScope().context().services().provide(MESSAGE, "first");
            second.rootScope().context().services().provide(MESSAGE, "second");
            var firstCalls = new AtomicInteger();
            var secondCalls = new AtomicInteger();
            first.rootScope().context().events().on(SIGNAL, firstCalls::incrementAndGet);
            second.rootScope().context().events().on(SIGNAL, secondCalls::incrementAndGet);

            first.rootScope().context().events().emit(SIGNAL, Signal::call);

            assertEquals("first",
                first.rootScope().context().services().require(MESSAGE));
            assertEquals("second",
                second.rootScope().context().services().require(MESSAGE));
            assertEquals(1, firstCalls.get());
            assertEquals(0, secondCalls.get());

            first.closeAsync().block(TIMEOUT);

            assertTrue(first.isClosed());
            assertFalse(second.isClosed());
            assertFalse(runtime.isClosed());
            assertEquals("second",
                second.rootScope().context().services().require(MESSAGE));
        }
    }

    @Test
    void rejectsAnExplicitTargetBoundToAnotherDomain() {
        try (var runtime = FibraRuntime.create()) {
            var first = runtime.openDomain("generation-1");
            var second = runtime.openDomain("generation-2");

            var failure = assertThrows(IllegalArgumentException.class, () ->
                first.rootScope().context().events().emit(
                    EventTarget.context(second.rootScope().context()), SIGNAL,
                    Signal::call));

            assertEquals("event target belongs to another runtime domain",
                failure.getMessage());
        }
    }

    @Test
    void snapshotsTypedServiceOwnershipAndEffectiveEventOrder() {
        try (var runtime = FibraRuntime.create()) {
            var domain = runtime.openDomain("generation-1");
            var provider = domain.rootScope().openChild("provider");
            var listener = domain.rootScope().openChild("listener");
            provider.context().services().provide(MESSAGE, "value");
            domain.rootScope().context().events().on(SIGNAL, () -> { });
            listener.context().events().once(SIGNAL, () -> { },
                EventOptions.prependGlobal());

            var snapshot = domain.snapshot();

            var service = snapshot.services().stream()
                .filter(value -> value.service().name().equals(MESSAGE.name()))
                .findFirst().orElseThrow();
            assertEquals(new RuntimeDomainSnapshot.OwnerIdentity("scope", "provider"),
                service.effectiveProvider());
            assertTrue(service.shadowedProviders().isEmpty());

            var event = snapshot.events().stream()
                .filter(value -> value.name().equals(SIGNAL.name()))
                .findFirst().orElseThrow();
            assertEquals(EventMode.EMIT, event.mode());
            assertEquals(Signal.class.getName(), event.listenerType());
            assertEquals(List.of(
                new RuntimeDomainSnapshot.Listener(
                    new RuntimeDomainSnapshot.OwnerIdentity("scope", "listener"),
                    0, true, true),
                new RuntimeDomainSnapshot.Listener(
                    new RuntimeDomainSnapshot.OwnerIdentity("scope", "generation-1"),
                    1, false, false)), event.listeners());
        }
    }

    @FunctionalInterface
    interface Signal {
        void call();
    }
}
