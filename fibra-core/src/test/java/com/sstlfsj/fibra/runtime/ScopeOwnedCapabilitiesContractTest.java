package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.event.EventKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScopeOwnedCapabilitiesContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MESSAGE = ServiceKey.of("message", String.class);
    private static final ServiceKey<Factory> FACTORY = ServiceKey.of("factory", Factory.class);
    private static final EventKey<Signal> SIGNAL = EventKey.of("signal", Signal.class);

    @Test
    void equalRealmLabelsResolveTheSameBindingWithoutLeakingToTheDefaultRealm() {
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            root.services().provide(MESSAGE, "default");
            var tenantA = root.withRealm(MESSAGE, "tenant-a");
            var sameTenant = root.withRealm(MESSAGE, new String("tenant-a"));

            assertFalse(tenantA.services().find(MESSAGE).isPresent());
            tenantA.services().provide(MESSAGE, "isolated");

            assertEquals("isolated", sameTenant.services().require(MESSAGE));
            assertEquals("default", root.services().require(MESSAGE));
        }
    }

    @Test
    void serviceInvocationResourcesBelongToTheCallerScope() {
        var disposed = new AtomicInteger();
        try (var runtime = FibraRuntime.create()) {
            runtime.rootScope().context().services().provide(FACTORY,
                invocation -> invocation.effects().add(
                    Disposables.from(disposed::incrementAndGet)));
            var request = runtime.rootScope().openChild("request");

            request.context().services().reference(FACTORY)
                .invoke((invocation, factory) -> {
                    factory.open(invocation);
                    return null;
                });
            request.closeAsync().block(TIMEOUT);

            assertEquals(1, disposed.get());
        }
    }

    @Test
    void eventAndEffectRegistrationsAreRevokedWithTheirScope() {
        var calls = new AtomicInteger();
        var order = new ArrayList<Integer>();
        try (var runtime = FibraRuntime.create()) {
            var session = runtime.rootScope().openChild("session");
            session.context().events().on(SIGNAL, calls::incrementAndGet);
            session.context().effects().collect(Flux.just(
                disposer(order, 1), disposer(order, 2)));

            runtime.rootScope().context().events().emit(SIGNAL, Signal::call);
            session.closeAsync().block(TIMEOUT);
            runtime.rootScope().context().events().emit(SIGNAL, Signal::call);

            assertEquals(1, calls.get());
            assertEquals(List.of(2, 1), order);
        }
    }

    private static Disposable disposer(List<Integer> order, int value) {
        return Disposables.from(() -> order.add(value));
    }

    @FunctionalInterface
    private interface Factory {
        void open(InvocationContext invocation);
    }

    @FunctionalInterface
    private interface Signal {
        void call();
    }
}
